package org.sedo.satmesh.nearby;

import static org.sedo.satmesh.signal.SignalManager.getAddress;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.nearby.connection.Payload;
import com.google.protobuf.ByteString;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.rt.BroadcastStatusEntry;
import org.sedo.satmesh.model.rt.BroadcastStatusEntryDao;
import org.sedo.satmesh.model.rt.RouteEntry;
import org.sedo.satmesh.model.rt.RouteEntry.RouteWithUsageTimestamp;
import org.sedo.satmesh.model.rt.RouteEntryDao;
import org.sedo.satmesh.model.rt.RouteRequestEntry;
import org.sedo.satmesh.model.rt.RouteRequestEntryDao;
import org.sedo.satmesh.model.rt.RouteUsage;
import org.sedo.satmesh.model.rt.RouteUsageDao;
import org.sedo.satmesh.proto.NearbyMessage;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.RouteRequestMessage;
import org.sedo.satmesh.proto.RouteResponseMessage;
import org.sedo.satmesh.proto.RoutedMessage;
import org.sedo.satmesh.proto.RoutedMessageBody;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeRepository.NodeCallback;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NearbyRouteManager {

	// --- Constants ---
	private static final long ROUTE_MAX_INACTIVITY_MILLIS = 12L * 60 * 60 * 1000; // 12 hours of inactivity before considering a route stale
	private static final int DEFAULT_ROUTE_HOPS = 10; // Default max hops for route discovery
	private static final long DEFAULT_ROUTE_TTL_MILLIS = 5L * 60 * 1000; // 5 minutes TTL for a route request message

	private static final String TAG = "NearbyRouteManager";

	private final NearbyManager nearbyManager;
	private final SignalManager signalManager;
	private final NodeRepository nodeRepository;
	private final RouteEntryDao routeEntryDao;
	private final RouteUsageDao routeUsageDao;
	private final RouteRequestEntryDao routeRequestEntryDao;
	private final BroadcastStatusEntryDao broadcastStatusEntryDao;
	private final ExecutorService executor;

	/**
	 * Constructor for NearbyRouteManager.
	 *
	 * @param nearbyManager Instance of NearbyManager for network operations.
	 * @param context       the application context
	 * @param executor      Executor service for background operations.
	 */
	protected NearbyRouteManager(
			@NonNull NearbyManager nearbyManager,
			@NonNull SignalManager signalManager,
			@NonNull Context context,
			@NonNull ExecutorService executor) {
		this.nearbyManager = nearbyManager;
		this.signalManager = signalManager;
		AppDatabase appDatabase = AppDatabase.getDB(context);
		this.nodeRepository = new NodeRepository(context);
		this.routeEntryDao = appDatabase.routeEntryDao();
		this.routeUsageDao = appDatabase.routeUsageDao();
		this.routeRequestEntryDao = appDatabase.routeRequestEntryDao();
		this.broadcastStatusEntryDao = appDatabase.broadcastStatusEntryDao();
		this.executor = executor;
	}

	/**
	 * Helper method to send a RouteRequestMessage.
	 * This method runs on an execution thread.
	 *
	 * @param recipientAddressName The SignalProtocolAddress.name of the recipient.
	 * @param messageBody          The RouteRequestMessage to send.
	 */
	private void sendRouteRequestMessage(@NonNull String recipientAddressName, @NonNull RouteRequestMessage messageBody) {
		executor.execute(() -> {
			try {
				Log.d(TAG, "Sending RouteRequestMessage to " + recipientAddressName + " for UUID: " + messageBody.getUuid());
				NearbyMessageBody nearbyMessageBody = NearbyMessageBody.newBuilder()
						.setMessageType(NearbyMessageBody.MessageType.ROUTE_DISCOVERY_REQ)
						.setEncryptedData(messageBody.toByteString())
						.build();
				// Encrypt message
				SignalProtocolAddress recipientAddress = getAddress(recipientAddressName);
				CiphertextMessage ciphertextMessage = signalManager.encryptMessage(recipientAddress, nearbyMessageBody.toByteArray());

				// Encapsulate the message
				NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
						.setExchange(false)
						.setBody(ByteString.copyFrom(ciphertextMessage.serialize())).build();

				// NearbyManager handles encryption and payload sending
				// The callback needs to handle the payload ID for tracking
				nearbyManager.sendNearbyMessageInternal(nearbyMessage, recipientAddressName, (payload, success) -> {
					if (success) {
						Log.d(TAG, "RouteRequestMessage payload " + payload.getId() + " sent successfully to " + recipientAddressName);
						Consumer<Node> save = node -> {
							// Ensure the broadcast status is recorded for tracking
							BroadcastStatusEntry broadcastStatus = new BroadcastStatusEntry(messageBody.getUuid(), node.getId());
							broadcastStatus.setPendingResponseInProgress(false); // Default to false
							broadcastStatusEntryDao.insert(broadcastStatus);
							Log.d(TAG, "BroadcastStatusEntry created for request " + messageBody.getUuid() + " to neighbor " + node.getId());
						};
						Node node = nodeRepository.findNodeSync(recipientAddressName);
						if (node == null) {
							Log.e(TAG, "Failed to find node for " + recipientAddressName + " after sending RouteRequestMessage. We are creating it.");
							Node newNode = new Node();
							newNode.setAddressName(recipientAddressName);
							nodeRepository.insert(newNode, ok -> {
								if (ok) {
									save.accept(newNode);
								} else {
									Log.w(TAG, "Failed to persist the node!");
								}
								// To fetch failing, caller of this method might try to retrieve occurrence of the broadcast in DB
							});
						} else {
							save.accept(node);
						}
					} else {
						Log.e(TAG, "Failed to send RouteRequestMessage payload " + payload.getId() + " to " + recipientAddressName);
						// To fetch failing, caller of this method might try to retrieve occurrence of the broadcast in DB
					}
				});
			} catch (Exception e) {
				Log.e(TAG, "Error building or sending RouteRequestMessage to " + recipientAddressName + ": " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Broadcasts a RouteRequestMessage to all currently connected neighbors,
	 * optionally excluding a specific sender.
	 * This method runs on the caller thread.
	 *
	 * @param routeRequestMessage      The RouteRequestMessage Protobuf object to be broadcasted.
	 * @param excludeSenderAddressName The SignalProtocolAddress.name of the sender to exclude from the broadcast (e.g., the previous hop).
	 *                                 Can be null or empty if no sender needs to be excluded.
	 * @return the number of neighbors this method tries to send the broadcast to.
	 */
	private int broadcastRouteRequestToNeighbors(
			@NonNull RouteRequestMessage routeRequestMessage, @Nullable String excludeSenderAddressName) {
		Log.d(TAG, "Broadcasting RouteRequest with UUID: " + routeRequestMessage.getUuid() +
				" to all neighbors (excluding: " + (excludeSenderAddressName != null ? excludeSenderAddressName : "none") + ")");

		int neighbors = 0;
		List<String> connectedNeighbors = nearbyManager.getConnectedEndpointsAddressNames();

		if (connectedNeighbors.isEmpty()) {
			Log.d(TAG, "No connected neighbors to broadcast RouteRequest to.");
			// No action needed here, the caller is responsible for handling lack of neighbors
			return neighbors;
		}

		for (String neighborAddressName : connectedNeighbors) {
			// Skip the excluded sender, if specified
			if (excludeSenderAddressName != null && excludeSenderAddressName.equals(neighborAddressName)) {
				Log.d(TAG, "Skipping excluded neighbor: " + neighborAddressName);
				continue;
			}

			/*
			 * Call the helper method to send the message to this specific neighbor
			 * The `sendRouteRequestMessage` method itself will handle the DB insertion
			 * for BroadcastStatusEntry upon successful payload send.
			 */
			sendRouteRequestMessage(neighborAddressName, routeRequestMessage);
			neighbors++;
		}
		return neighbors;
	}

	/**
	 * Initiates a new route discovery process to find a path to a specified destination node.
	 * This method is called by a higher-level component (e.g., a messaging service)
	 * when a route is needed for a specific destination.
	 * This method first checks for an existing usable route. If no such route is found,
	 * it generates a new route request UUID, stores the request locally, and broadcasts it to neighbors.
	 * This method runs on the executor thread.
	 *
	 * @param destinationNodeAddressName   The SignalProtocolAddress.name of the node to which a route is desired.
	 * @param onRouteFoundCallback         A callback executed if an existing, usable route is found.
	 *                                     The RouteEntry object representing the found route is passed to the callback.
	 * @param onDiscoveryInitiatedCallback A callback executed to inform the caller about the status of the new discovery.
	 *                                     A {@code true} value indicates the request was broadcasted to at least one neighbor.
	 *                                     A {@code false} value indicates the discovery could not be initiated (e.g., no neighbors).
	 */
	public void initiateRouteDiscovery(
			@NonNull String destinationNodeAddressName,
			@NonNull Consumer<RouteEntry> onRouteFoundCallback,
			@Nullable Consumer<Boolean> onDiscoveryInitiatedCallback) {
		executor.execute(() -> {
			Log.d(TAG, "Initiating route discovery for destination: " + destinationNodeAddressName);

			// 1. Resolve destinationNodeAddressName to destinationNodeLocalId
			Node destinationNode = nodeRepository.findNodeSync(destinationNodeAddressName);
			if (destinationNode == null) {
				Log.e(TAG, "Destination node " + destinationNodeAddressName + " not found in local repository. Cannot initiate route discovery.");
				// Inform caller that discovery could not be initiated
				if (onDiscoveryInitiatedCallback != null) {
					onDiscoveryInitiatedCallback.accept(false);
				}
				return;
			}
			long destinationNodeLocalId = destinationNode.getId();

			// 2. Check for an existing, active, and recently used route to the destination
			RouteWithUsageTimestamp routeWithUsage = routeEntryDao.getMostRecentOpenedRouteByDestinationSync(destinationNodeLocalId);

			if (routeWithUsage != null && routeWithUsage.getRouteEntry().isOpened()) {
				long lastUsedTimestamp = routeWithUsage.getLastUsedTimestamp();
				long currentTime = System.currentTimeMillis();

				if (currentTime - lastUsedTimestamp <= ROUTE_MAX_INACTIVITY_MILLIS) {
					Log.d(TAG, "Existing active and recently used route found for " + destinationNodeAddressName +
							". Route UUID: " + routeWithUsage.getRouteEntry().getDiscoveryUuid() +
							", last used " + ((currentTime - lastUsedTimestamp) / 1000) + " seconds ago.");
					// Notify the higher layer that a route is available
					onRouteFoundCallback.accept(routeWithUsage.getRouteEntry());
					return;
				} else {
					Log.d(TAG, "Existing route for " + destinationNodeAddressName + " found, but it is stale " +
							"(" + ((currentTime - lastUsedTimestamp) / 1000) + " seconds old). Initiating new discovery.");
				}
			} else {
				Log.d(TAG, "No existing active route found for " + destinationNodeAddressName + ". Initiating new discovery.");
			}

			// If we reach here, either no route was found, or the existing one was stale.
			// Proceed with new route discovery.

			// 3. Generate a new UUID for the route request
			String requestUuid = UUID.randomUUID().toString();

			// 4. Store the initial RouteRequestEntry
			RouteRequestEntry newRequestEntry = new RouteRequestEntry(requestUuid);
			newRequestEntry.setDestinationNodeLocalId(destinationNodeLocalId);
			newRequestEntry.setPreviousHopLocalId(null); // Null because this is the original source node
			routeRequestEntryDao.insert(newRequestEntry);
			Log.d(TAG, "New RouteRequestEntry created for UUID: " + requestUuid);

			// 5. Construct the RouteRequestMessage Protobuf
			// Use localHostAddress provided as a parameter for sourceNodeId
			RouteRequestMessage routeRequest = RouteRequestMessage.newBuilder()
					.setUuid(requestUuid)
					.setDestinationNodeId(destinationNodeAddressName)
					.setRemainingHops(DEFAULT_ROUTE_HOPS)
					.setMaxTtl(System.currentTimeMillis() + DEFAULT_ROUTE_TTL_MILLIS)
					.build();

			// 6. Broadcast the request to all neighbors (no exclusion for initial broadcast)
			int sentToNeighborsCount = broadcastRouteRequestToNeighbors(routeRequest, null);

			// Inform caller about the discovery initiation status
			if (onDiscoveryInitiatedCallback != null) {
				onDiscoveryInitiatedCallback.accept(sentToNeighborsCount > 0);
			}

			if (sentToNeighborsCount == 0) {
				Log.w(TAG, "Route request " + requestUuid + " initiated, but no neighbors to broadcast to. Discovery might fail.");
				routeRequestEntryDao.deleteByRequestUuid(requestUuid);
			}
		});
	}

	/**
	 * Helper method to send a RouteResponseMessage.
	 * This method runs on caller thread.
	 *
	 * @param recipientAddressName The SignalProtocolAddress.name of the recipient.
	 * @param messageBody          The RouteResponseMessage to send.
	 * @param sendingCallback      A callback to be invoked with the Payload and a boolean indicating
	 *                             success (true) or failure (false) of the underlying Nearby message send.
	 */
	private void sendRouteResponseMessage(
			@NonNull String recipientAddressName,
			@NonNull RouteResponseMessage messageBody, @NonNull BiConsumer<Payload, Boolean> sendingCallback) {
		try {
			Log.d(TAG, "Sending RouteResponseMessage to " + recipientAddressName + " for UUID: " + messageBody.getRequestUuid() + " with status: " + messageBody.getStatus());
			NearbyMessageBody nearbyMessageBody = NearbyMessageBody.newBuilder()
					.setMessageType(NearbyMessageBody.MessageType.ROUTE_DISCOVERY_RESP)
					.setEncryptedData(messageBody.toByteString())
					.build();

			// Encrypt message
			SignalProtocolAddress recipientAddress = getAddress(recipientAddressName);
			CiphertextMessage ciphertextMessage = signalManager.encryptMessage(recipientAddress, nearbyMessageBody.toByteArray());

			// Encapsulate the message
			NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
					.setExchange(false)
					.setBody(ByteString.copyFrom(ciphertextMessage.serialize())).build();

			nearbyManager.sendNearbyMessageInternal(nearbyMessage, recipientAddressName, sendingCallback);
		} catch (Exception e) {
			Log.e(TAG, "Error building or sending RouteResponseMessage to " + recipientAddressName + ": " + e.getMessage(), e);
			sendingCallback.accept(null, false);
		}
	}

	/**
	 * Handles an incoming RouteRequestMessage.
	 * This method is called by NearbySignalMessenger after decrypting a {@code ROUTE_DISCOVERY_REQ}.
	 *
	 * @param senderAddressName The SignalProtocolAddress.name of the sender node.
	 * @param routeRequest      The parsed RouteRequestMessage Protobuf object.
	 * @param localHostAddress  The SignalProtocolAddress.name of the local host Node.
	 */
	public void handleIncomingRouteRequest(
			@NonNull String senderAddressName,
			@NonNull RouteRequestMessage routeRequest,
			@NonNull String localHostAddress) {
		executor.execute(() -> {
			Log.d(TAG, "Handling incoming RouteRequestMessage from " + senderAddressName +
					" for UUID: " + routeRequest.getUuid() +
					", Destination: " + routeRequest.getDestinationNodeId() +
					", Remaining Hops: " + routeRequest.getRemainingHops());

			// Implement the detailed logic for RouteRequestMessage handling:
			// This method processes an incoming route request, acting as either an intermediate node or the final destination.
			// 1. Resolve sender's address name to a local Node ID, creating the node if it's new.
			// 2. Check if the RouteRequest UUID already exists in RouteRequestEntry.
			//    - If found, it's a duplicate; send a "REQUEST_ALREADY_IN_PROGRESS" response to the sender.
			// 3. Determine if the current node is the intended destination for the route request.
			//    - If yes, a route has been found! Send a "ROUTE_FOUND" response to the sender (previous hop).
			// 4. If the current node is not the destination (it's an intermediate node):
			//    - Check the message's TTL (Time-To-Live) and remaining_hops.
			//      - If expired or zero hops, send "TTL_EXPIRED" or "MAX_HOPS_REACHED" response to the sender.
			//    - If valid for relaying:
			//      - Store the incoming RouteRequestEntry to remember the previous hop for the response path.
			//      - Decrement the remaining_hops count.
			//      - Forward (broadcast) the updated RouteRequestMessage to all other connected neighbors,
			//        excluding the node from which this request was received.
			//      - If no other neighbors are available for relay, send a "NO_ROUTE_FOUND" response back to the sender.

			// 1. Resolve sender's address name to a local Node ID, creating the node if it's new.
			nodeRepository.findOrCreateNodeAsync(senderAddressName, new NodeCallback() {
				@Override
				public void onNodeReady(@NonNull Node senderNode) {
					long senderNodeLocalId = senderNode.getId();
					String resolvedSenderAddressName = senderNode.getAddressName();
					Log.d(TAG, "Resolved sender " + resolvedSenderAddressName + " to local ID: " + senderNodeLocalId + ". Continuing RouteRequest processing for UUID: " + routeRequest.getUuid());

					// 2. Check if UUID exists in RouteRequestEntry (prevent loops/redundant processing).
					RouteRequestEntry existingRequest = routeRequestEntryDao.getRequestByUuid(routeRequest.getUuid());

					if (existingRequest != null) {
						Log.d(TAG, "RouteRequest with UUID " + routeRequest.getUuid() + " already in progress. Sending REQUEST_ALREADY_IN_PROGRESS response.");
						RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
								.setRequestUuid(routeRequest.getUuid())
								.setStatus(RouteResponseMessage.Status.REQUEST_ALREADY_IN_PROGRESS)
								.build();

						sendRouteResponseMessage(resolvedSenderAddressName, responseMessage, (payload, success) -> {
							if (success) {
								Log.d(TAG, "Sent REQUEST_ALREADY_IN_PROGRESS response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
							} else {
								Log.e(TAG, "Failed to send REQUEST_ALREADY_IN_PROGRESS response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
							}
						});
						return; // Stop processing, request is redundant.
					}

					// 3. Check if current node is the destination.
					if (localHostAddress.equals(routeRequest.getDestinationNodeId())) {
						Log.d(TAG, "Current node is the destination for RouteRequest " + routeRequest.getUuid() + ". Sending ROUTE_FOUND response.");

						// Send ROUTE_FOUND response back to the previous hop (sender of this request)
						RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
								.setRequestUuid(routeRequest.getUuid())
								.setStatus(RouteResponseMessage.Status.ROUTE_FOUND)
								.setHopCount(0)
								.build();
						sendRouteResponseMessage(resolvedSenderAddressName, responseMessage, (payload, success) -> {
							if (success) {
								Log.d(TAG, "Sent ROUTE_FOUND response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
							} else {
								Log.e(TAG, "Failed to send ROUTE_FOUND response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
							}
						});
						return; // Stop processing, route found.
					}

					// If not the destination, proceed to check TTL and remaining hops for relaying.
					// 4. Check TTL and remaining_hops.
					long currentTime = System.currentTimeMillis();
					if (routeRequest.getMaxTtl() < currentTime) {
						Log.d(TAG, "RouteRequest " + routeRequest.getUuid() + " has expired (TTL reached). Sending TTL_EXPIRED response.");
						RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
								.setRequestUuid(routeRequest.getUuid())
								.setStatus(RouteResponseMessage.Status.TTL_EXPIRED)
								.build();
						sendRouteResponseMessage(resolvedSenderAddressName, responseMessage, (payload, success) -> {
							if (success) {
								Log.d(TAG, "Sent TTL_EXPIRED response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
							} else {
								Log.e(TAG, "Failed to send TTL_EXPIRED response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
							}
						});
						return; // Stop processing.
					}

					if (routeRequest.getRemainingHops() <= 0) {
						Log.d(TAG, "RouteRequest " + routeRequest.getUuid() + " has reached max hops. Sending MAX_HOPS_REACHED response.");
						RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
								.setRequestUuid(routeRequest.getUuid())
								.setStatus(RouteResponseMessage.Status.MAX_HOPS_REACHED)
								.build();
						sendRouteResponseMessage(resolvedSenderAddressName, responseMessage, (payload, success) -> {
							if (success) {
								Log.d(TAG, "Sent MAX_HOPS_REACHED response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
							} else {
								Log.e(TAG, "Failed to send MAX_HOPS_REACHED response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
							}
						});
						return; // Stop processing.
					}

					// --- If we reach here, this is an intermediate node and the request is valid for relaying ---

					// 5. Store the incoming RouteRequestEntry
					RouteRequestEntry newRequestEntry = new RouteRequestEntry(routeRequest.getUuid());
					// Ensure destination exists
					nodeRepository.findOrCreateNodeAsync(routeRequest.getDestinationNodeId(), new NodeCallback() {
						@Override
						public void onNodeReady(@NonNull Node destination) {
							newRequestEntry.setDestinationNodeLocalId(destination.getId());
							newRequestEntry.setPreviousHopLocalId(senderNodeLocalId); // The node that sent us this request
							routeRequestEntryDao.insert(newRequestEntry);
							Log.d(TAG, "RouteRequestEntry stored for relaying: UUID=" + routeRequest.getUuid() + ", PrevHop=" + resolvedSenderAddressName);

							// 6. Decrement hops and relay the request to other neighbors
							int newRemainingHops = routeRequest.getRemainingHops() - 1;
							RouteRequestMessage relayedRouteRequest = RouteRequestMessage.newBuilder()
									.setUuid(routeRequest.getUuid())
									.setDestinationNodeId(routeRequest.getDestinationNodeId())
									.setRemainingHops(newRemainingHops)
									.setMaxTtl(routeRequest.getMaxTtl())
									.build();

							// Broadcast the request to all neighbors, excluding the sender of this request
							int sentToNeighborsCount = broadcastRouteRequestToNeighbors(relayedRouteRequest, resolvedSenderAddressName);

							if (sentToNeighborsCount == 0) {
								Log.w(TAG, "Route request " + routeRequest.getUuid() + " received, but no other neighbors to relay to. Sending NO_ROUTE_FOUND response back.");
								// If no other neighbors to relay to, this branch of discovery ends here.
								RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
										.setRequestUuid(routeRequest.getUuid())
										.setStatus(RouteResponseMessage.Status.NO_ROUTE_FOUND)
										.build();
								sendRouteResponseMessage(resolvedSenderAddressName, responseMessage, (payload, success) -> {
									if (success) {
										Log.d(TAG, "Sent NO_ROUTE_FOUND response for " + routeRequest.getUuid() + " (no relays) to " + resolvedSenderAddressName);
									} else {
										Log.e(TAG, "Failed to send NO_ROUTE_FOUND response for " + routeRequest.getUuid() + " to " + resolvedSenderAddressName);
									}
								});
							} else {
								Log.d(TAG, "Route request " + routeRequest.getUuid() + " relayed to " + sentToNeighborsCount + " neighbors. New hops: " + newRemainingHops);
							}
						}

						@Override
						public void onError(@NonNull String errorMessage) {
							Log.e(TAG, "Failed to resole the destination Node on the intermediate node of address=" + localHostAddress + ". Error: " + errorMessage);
						}
					});
				}

				@Override
				public void onError(@NonNull String errorMessage) {
					Log.e(TAG, "Failed to resolve or create sender node " + senderAddressName + ": " + errorMessage + ". Aborting RouteRequest processing.");
				}
			});
		});
	}

	/**
	 * Handles an incoming RouteResponseMessage.
	 * This method is called by NearbySignalMessenger after decrypting a ROUTE_DISCOVERY_RESP.
	 * This method runs on the executor thread.
	 *
	 * @param senderAddressName The SignalProtocolAddress.name of the sender node (previous hop on the return path).
	 * @param routeResponse     The parsed RouteResponseMessage Protobuf object.
	 */
	public void handleIncomingRouteResponse(
			@NonNull String senderAddressName,
			@NonNull RouteResponseMessage routeResponse) {
		executor.execute(() -> {
			Log.d(TAG, "Handling incoming RouteResponseMessage from " + senderAddressName +
					" for Request UUID: " + routeResponse.getRequestUuid() +
					", Status: " + routeResponse.getStatus());

			// handleIncomingRouteResponse Algorithm:

			// 1. Extract UUID from the request, senderNode (the node that sent the response), and status.
			// 2. Search for the corresponding RouteRequestEntry for the UUID.
			//    a. If no RouteRequestEntry is found (error, late response, or request already processed and deleted),
			//       log the event and ignore the response. Return.
			//    b. Retrieve N_previous_hop_of_the_request from the RouteRequestEntry. (This will be null if
			//       N_current is the original source node that initiated the request).
			//    c. Retrieve N_destination_of_the_the_request from the RouteRequestEntry.
			// 3. Identify the BroadcastStatusEntry for the UUID and senderNode.
			//    a. If not found (unexpected behavior, a response should not arrive without a prior broadcast),
			//       log the error and stop processing this unexpected response. Return.

			// 4. If status == ROUTE_FOUND:
			//    a. Delete the RouteRequestEntry corresponding to the UUID from DB.
			//    b. Create a RouteEntry (at the N_current node):
			//       i. Destination of this RouteEntry = N_destination_of_the_request (the final destination of the original request).
			//       ii. Next_hop of this RouteEntry = N_sender of this response (the node that just sent us the ROUTE_FOUND).
			//       iii. Hop count = message_response_route.hop_count (the total hops from the original source).
			//       iv. Save the RouteEntry in the database.
			//    c. Mark the RouteEntry as "opened" and update the usage timestamp by recording a RouteUsage based on the request's UUID.
			//    d. Delete all pending BroadcastStatusEntry for this UUID.
			//    e. If N_previous_hop_of_the_request is NULL (meaning N_current is the original source node):
			//       i. The route is established. Use a dedicated method `NearbyManager.onRouteFound` which will be
			//          responsible for necessary processing.
			//    f. Else (N_current is an intermediate node):
			//       i. Forward the message_response_route (with ROUTE_FOUND status) to N_previous_hop_of_the_request after increment the hop_count

			// 5. If status == REQUEST_ALREADY_IN_PROGRESS:
			//    a. Update the BroadcastStatusEntry for the UUID and N_sender: set isPendingResponseInProgress = true.
			//    b. Check if there are other BroadcastStatusEntry entries for this UUID with (isPendingResponseInProgress == false),
			//       i.e., not yet processed.
			//    c. If no BroadcastStatusEntry with (isPendingResponseInProgress == false) is found for this UUID
			//       (meaning all BroadcastStatusEntry entries have just been processed for this request_uuid):
			//       i. Delete any remaining BroadcastStatusEntry entries.
			//       ii. Delete the RouteRequestEntry.
			//       iii. If N_previous_hop_of_the_request is NULL (N_current is the original source node):
			//          1. Undesirable behavior: given that the source is the sole holder of the request,
			//             it's not normal for an immediate neighbor to respond this way.
			//          2. It looks like a UUID collision. Log the error.
			//          3. Notify the upper layer via `NearbyManager.onRouteNotFound`. Return.
			//       iv. Else (N_current is an intermediate node):
			//          1. Forward the original message_response_route (with REQUEST_ALREADY_IN_PROGRESS status)
			//             to N_previous_hop_of_the_request.
			//    d. Else (there are still other pending responses), do nothing and wait for them. Return.

			// 6. If status is a failure (NO_ROUTE_FOUND, TTL_EXPIRED, MAX_HOPS_REACHED):
			//    a. Delete the BroadcastStatusEntry for the UUID and N_sender. (This branch is a definitive failure).
			//    b. Check if there is still at least one BroadcastStatusEntry for this UUID that is not yet processed
			//       (i.e., isPendingResponseInProgress == false).
			//    i. If no unprocessed BroadcastStatusEntry is found for this UUID:
			//       1. Delete the RouteRequestEntry.
			//       2. Check if there is a BroadcastStatusEntry in the state isPendingResponseInProgress == true:
			//          a. If found, execute `sequence(REQUEST_ALREADY_IN_PROGRESS)`.
			//          b. Else, execute `sequence(the_same_received_status)`.

			//       sequence(response_status) {
			//          i. Delete all remaining occurrences of BroadcastStatusEntry.
			//          ii. If N_previous_hop_of_the_request is NULL (N_current is the original source node):
			//             1. Notify the upper layer that the route could not be found: `NearbyManager.onRouteNotFound`.
			//          iii. Else (N_current is an intermediate node):
			//             1. Forward the original message_response_route (with `response_status`)
			//                to N_previous_hop_of_the_request.
			//       }
			//    ii. Else (there are remaining responses), wait for them. Return.

			String requestUuid = routeResponse.getRequestUuid();
			RouteResponseMessage.Status responseStatus = routeResponse.getStatus();

			// 1.
			nodeRepository.findOrCreateNodeAsync(senderAddressName, new NodeCallback() {
				@Override
				public void onNodeReady(@NonNull Node senderNode) {
					long senderNodeLocalId = senderNode.getId();
					String resolvedSenderAddressName = senderNode.getAddressName();

					// 2.
					RouteRequestEntry routeRequestEntry = routeRequestEntryDao.getRequestByUuid(requestUuid);
					if (routeRequestEntry == null) {
						Log.w(TAG, "RouteResponseMessage with UUID " + requestUuid + " received, but no corresponding RouteRequestEntry found. Ignoring response.");
						return;
					}

					long destinationNodeLocalId = routeRequestEntry.getDestinationNodeLocalId();
					Long previousHopLocalId = routeRequestEntry.getPreviousHopLocalId(); // Null if current node is original source

					// Get destination node address name for logging and callbacks
					Node destinationNode = nodeRepository.findNodeSync(destinationNodeLocalId);
					if (destinationNode == null) {
						// Undesired behavior, the node must be set at route request entry mapping
						Log.e(TAG, "Undesired behavior, the node must be set at route request entry mapping");
						return;
					}
					String destinationNodeAddressName = destinationNode.getAddressName();

					// 3.
					BroadcastStatusEntry broadcastStatus = broadcastStatusEntryDao.getBroadcastStatusEntrySync(requestUuid, senderNodeLocalId);
					if (broadcastStatus == null) {
						Log.e(TAG, "RouteResponseMessage for UUID " + requestUuid + " from " + resolvedSenderAddressName + " received, but no corresponding BroadcastStatusEntry found. This is unexpected. Ignoring response.");
						return;
					}

					// Helper to determine if current node is the original source
					boolean isOriginalSource = (previousHopLocalId == null);
					Node previousHopNode = previousHopLocalId != null ? nodeRepository.findNodeSync(previousHopLocalId) : null;

					// --- Internal helper function for common error/completion sequences ---
					// This function simplifies the logic for handling various failure scenarios
					// where a response needs to be forwarded or the source needs to be notified.
					Consumer<RouteResponseMessage.Status> handleCompletionSequence = (finalStatus) -> {
						broadcastStatusEntryDao.deleteAllByRequestUuid(requestUuid);
						Log.d(TAG, "Deleted all BroadcastStatusEntries for UUID: " + requestUuid);

						routeRequestEntryDao.deleteByRequestUuid(requestUuid);
						Log.d(TAG, "Deleted RouteRequestEntry for UUID: " + requestUuid);

						if (isOriginalSource) {
							// Notify the higher layer that the route could not be found.
							nearbyManager.onRouteNotFound(requestUuid, destinationNodeAddressName, finalStatus);
							Log.d(TAG, "Notified NearbyManager.onRouteNotFound for UUID " + requestUuid + " with status: " + finalStatus);
						} else {
							if (previousHopNode == null) {
								Log.e(TAG, "Failed to retrieve previous hop node " + previousHopLocalId + " for forwarding response for UUID " + requestUuid);
								return;
							}
							RouteResponseMessage responseToForward = RouteResponseMessage.newBuilder()
									.setRequestUuid(requestUuid)
									.setStatus(finalStatus)
									.build();
							sendRouteResponseMessage(previousHopNode.getAddressName(), responseToForward, (p, s) -> {
								if (s) {
									Log.d(TAG, "Forwarded response with status " + finalStatus + " for UUID " + requestUuid + " to previous hop " + previousHopNode.getAddressName());
								} else {
									Log.e(TAG, "Failed to forward response with status " + finalStatus + " for UUID " + requestUuid + " to previous hop " + previousHopNode.getAddressName());
								}
							});
						}
					};

					// 4. If status == ROUTE_FOUND:
					if (responseStatus == RouteResponseMessage.Status.ROUTE_FOUND) {
						routeRequestEntryDao.deleteByRequestUuid(requestUuid);
						Log.d(TAG, "Deleted RouteRequestEntry for UUID: " + requestUuid + " (ROUTE_FOUND)");

						// 4.b.
						RouteEntry newRouteEntry = new RouteEntry();
						newRouteEntry.setDiscoveryUuid(requestUuid);
						newRouteEntry.setDestinationNodeLocalId(destinationNodeLocalId);
						newRouteEntry.setNextHopLocalId(senderNodeLocalId);
						newRouteEntry.setHopCount(routeResponse.getHopCount());
						newRouteEntry.setPreviousHopLocalId(previousHopLocalId);
						routeEntryDao.insert(newRouteEntry);
						Log.d(TAG, "RouteEntry created for UUID: " + requestUuid + " with destination " + destinationNodeAddressName + ", next hop " + resolvedSenderAddressName);

						// 4.c.
						RouteUsage routeUsage = new RouteUsage(requestUuid);
						routeUsage.setRouteEntryDiscoveryUuid(requestUuid); // Same as request ID at route discovery
						routeUsage.setLastUsedTimestamp(System.currentTimeMillis());
						routeUsageDao.insert(routeUsage);
						Log.d(TAG, "RouteUsage recorded for UUID: " + requestUuid);

						// 4.d.
						broadcastStatusEntryDao.deleteAllByRequestUuid(requestUuid);
						Log.d(TAG, "Deleted all BroadcastStatusEntries for UUID: " + requestUuid + " (ROUTE_FOUND)");

						// 4.e.
						if (isOriginalSource) {
							// The route is established. Use a dedicated method NearbyManager.onRouteFound
							nearbyManager.onRouteFound(destinationNodeAddressName, newRouteEntry);
							Log.d(TAG, "Notified NearbyManager.onRouteFound for UUID " + requestUuid + " to " + destinationNodeAddressName);
						} else {
							// 4.f.
							if (previousHopNode == null) {
								Log.e(TAG, "Failed to retrieve previous hop node " + previousHopLocalId + " for forwarding ROUTE_FOUND response for UUID " + requestUuid);
								return;
							}
							sendRouteResponseMessage(previousHopNode.getAddressName(), routeResponse, (p, s) -> {
								if (s) {
									Log.d(TAG, "Forwarded ROUTE_FOUND response for UUID " + requestUuid + " to previous hop " + previousHopNode.getAddressName());
								} else {
									Log.e(TAG, "Failed to forward ROUTE_FOUND response for UUID " + requestUuid + " to previous hop " + previousHopNode.getAddressName());
								}
							});
						}
					} else if (responseStatus == RouteResponseMessage.Status.REQUEST_ALREADY_IN_PROGRESS) {
						// 5.
						// 5.a.
						broadcastStatus.setPendingResponseInProgress(true); // Mark as processed for this neighbor
						broadcastStatusEntryDao.update(broadcastStatus);
						Log.d(TAG, "BroadcastStatusEntry for " + requestUuid + " to " + resolvedSenderAddressName + " updated to isPendingResponseInProgress=true.");

						// 5.b.
						boolean isBroadcastRemaining = broadcastStatusEntryDao.hasResponseInProgressState(requestUuid, false);

						// 5.c.
						if (!isBroadcastRemaining) {
							// All broadcasts have been processed

							if (isOriginalSource) {
								// 5.c.i.
								Log.e(TAG, "Unexpected REQUEST_ALREADY_IN_PROGRESS response for UUID " + requestUuid + " at original source. Potential UUID collision.");
							}
							handleCompletionSequence.accept(RouteResponseMessage.Status.REQUEST_ALREADY_IN_PROGRESS);
						} else {
							// 5.d.
							Log.d(TAG, "Still pending broadcasts for UUID " + requestUuid + ". Waiting for more responses.");
						}
					} else {
						// 6. Status in (NO_ROUTE_FOUND, TTL_EXPIRED, MAX_HOPS_REACHED)
						// 6.a
						broadcastStatusEntryDao.delete(requestUuid, broadcastStatus.getNeighborNodeLocalId());
						Log.d(TAG, "BroadcastStatusEntry for " + requestUuid + " to " + resolvedSenderAddressName + " deleted (failure).");

						// 6.b.
						boolean isBroadcastRemaining = broadcastStatusEntryDao.hasResponseInProgressState(requestUuid, false);

						// 6.b.i.
						if (!isBroadcastRemaining) {
							// All branches have responded.
							boolean isRequestInProgress = broadcastStatusEntryDao.hasResponseInProgressState(requestUuid, true);

							if (isRequestInProgress) {
								Log.d(TAG, "All branches processed for UUID " + requestUuid + ". Found REQUEST_ALREADY_IN_PROGRESS branches. Executing completion with REQUEST_ALREADY_IN_PROGRESS.");
								handleCompletionSequence.accept(RouteResponseMessage.Status.REQUEST_ALREADY_IN_PROGRESS);
							} else {
								Log.d(TAG, "All branches processed for UUID " + requestUuid + ". No REQUEST_ALREADY_IN_PROGRESS branches. Executing completion with received status: " + responseStatus);
								handleCompletionSequence.accept(responseStatus);
							}
						} else {
							// 6.b.ii.
							Log.d(TAG, "Still pending broadcasts for UUID " + requestUuid + ". Waiting for more responses.");
						}
					}
				}

				@Override
				public void onError(@NonNull String errorMessage) {
					Log.e(TAG, "Failed to resolve or create sender node " + senderAddressName + ": " + errorMessage + ". Aborting RouteResponseMessage processing.");
				}
			});
		});
	}

	// Routed messages exchange methods

	/**
	 * Helper method to send a RoutedMessage encapsulated in an OUTER NearbyMessageBody.
	 * This method runs on caller thread (which is usually the executor thread for other methods in this class).
	 */
	private void sendRoutedMessageToNextHop(
			@NonNull String nextHopAddressName,
			@NonNull NearbyMessageBody outerNearbyMessageBody, // The NearbyMessageBody with type ROUTED_MESSAGE
			@Nullable BiConsumer<Payload, Boolean> callback
	) {
		try {
			Log.d(TAG, "Sending RoutedMessage (outer) to next hop: " + nextHopAddressName);

			// Encrypt the OUTER NearbyMessageBody for the next hop (hop-by-hop encryption)
			SignalProtocolAddress nextHopAddress = getAddress(nextHopAddressName);
			CiphertextMessage ciphertextMessage = signalManager.encryptMessage(nextHopAddress, outerNearbyMessageBody.toByteArray());

			// Encapsulate into the final NearbyMessage
			NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
					.setExchange(false)
					.setBody(ByteString.copyFrom(ciphertextMessage.serialize()))
					.build();

			// Use NearbyManager to send the raw payload bytes
			BiConsumer<Payload, Boolean> finalCallback = callback != null ? callback : (payload, success) -> {
				if (success) {
					Log.d(TAG, "RoutedMessage payload " + payload.getId() + " sent successfully to " + nextHopAddressName);
				} else {
					Log.e(TAG, "Failed to send RoutedMessage payload " + payload.getId() + " to " + nextHopAddressName);
				}
			};
			nearbyManager.sendNearbyMessageInternal(nearbyMessage, nextHopAddressName, finalCallback);
		} catch (Exception e) {
			Log.e(TAG, "Error building or sending RoutedMessage to " + nextHopAddressName + ": " + e.getMessage(), e);
		}
	}

	protected RouteAndUsage getIfExistRouteAndUsageFor(@NonNull String remoteAddressName) {
		// 1. Resolve finalDestinationAddressName to local ID
		Node finalDestinationNode = nodeRepository.findNodeSync(remoteAddressName);
		if (finalDestinationNode == null) {
			Log.e(TAG, "Final destination node " + remoteAddressName + " not found in local repository.");
			return null;
		}

		// 2. Find an active route to the final destination
		RouteWithUsageTimestamp entryW = routeEntryDao.getMostRecentOpenedRouteByDestinationSync(finalDestinationNode.getId());
		if (entryW == null) {
			Log.e(TAG, "No active route found for destination " + remoteAddressName + ". Please initiate route discovery.");
			return null;
		}
		if (!entryW.routeEntry.isOpened()) {
			Log.w(TAG, "Route found but unusable.");
			return null;
		}
		RouteUsage routeUsage = routeUsageDao.getMostRecentRouteUsageForDestinationSync(finalDestinationNode.getId());
		if (routeUsage == null) {
			Log.e(TAG, "Impossible to find the RouteUsage for destination " + remoteAddressName + ". Please initiate route discovery.");
			return null;
		}
		long lastUsageTimestamp;
		if (routeUsage.getLastUsedTimestamp() == null) {
			// Undesired behavior
			lastUsageTimestamp = System.currentTimeMillis();
			routeUsage.setLastUsedTimestamp(lastUsageTimestamp);
			routeUsageDao.update(routeUsage);
		} else {
			lastUsageTimestamp = routeUsage.getLastUsedTimestamp();
		}
		long delay = System.currentTimeMillis() - lastUsageTimestamp;
		if (delay > ROUTE_MAX_INACTIVITY_MILLIS) {
			Log.w(TAG, "Match expired route. Delete it.");
			routeUsageDao.deleteUsagesForRouteEntry(entryW.routeEntry.getDiscoveryUuid());
			routeEntryDao.delete(entryW.routeEntry);
			return null;
		}
		return new RouteAndUsage(entryW.routeEntry, routeUsage);
	}

	/**
	 * Initiates the process of sending an application-level message (contained in
	 * {@code internalNearbyMessageBody}) from the {@code originalSenderAddressName}
	 * to the {@code finalDestinationAddressName} via an established route.
	 * <p>
	 * This method first encapsulates the {@code internalNearbyMessageBody} and
	 * {@code originalSenderAddressName} into a {@link RoutedMessageBody},
	 * which is then end-to-end encrypted for the {@code finalDestinationAddressName}.
	 * This encrypted payload is then wrapped within a {@link RoutedMessage}
	 * along with routing information (route UUID, usage UUID).
	 * Finally, this {@link RoutedMessage} is sent to the
	 * determined next hop along the route, with hop-by-hop encryption/decryption.
	 * <p>
	 * The method queries the local route table to find the most recent active route
	 * to the {@code finalDestinationAddressName} and resolves the immediate next hop.
	 *
	 * @param finalDestinationAddressName The {@code SignalProtocolAddress.name} of the ultimate
	 *                                    recipient node for this message.
	 * @param originalSenderAddressName   The {@code SignalProtocolAddress.name} of the node
	 *                                    that originally sent this message.
	 * @param internalNearbyMessageBody   The {@link NearbyMessageBody} representing the actual
	 *                                    application-level message (e.g., chat message, personal info, ACK). This object
	 *                                    is *unencrypted* at this stage and will be encrypted end-to-end within this method.
	 * @param callback                    A {@link BiConsumer} callback that will be invoked with the Nearby Connections
	 *                                    {@link Payload} and a boolean indicating whether the transmission to the {@code next hop}
	 *                                    was successful ({@code true}) or failed ({@code false}).
	 */
	public void sendMessageThroughRoute(
			@NonNull String finalDestinationAddressName,
			@NonNull String originalSenderAddressName,
			@NonNull NearbyMessageBody internalNearbyMessageBody, // The actual message, UNENCRYPTED
			@NonNull BiConsumer<Payload, Boolean> callback
	) {
		executor.execute(() -> {
			Log.d(TAG, "Attempting to send message to " + finalDestinationAddressName + " from " + originalSenderAddressName);

			// 1. Resolve finalDestinationAddressName to local ID
			// 2. Find an active route to the final destination
			RouteAndUsage routeAndUsage = getIfExistRouteAndUsageFor(finalDestinationAddressName);
			if (routeAndUsage == null) {
				Log.e(TAG, "No active pair route-usage found for destination " + finalDestinationAddressName + ". Please initiate route discovery.");
				callback.accept(null, false);
				return;
			}
			RouteEntry activeRoute = routeAndUsage.routeEntry;
			RouteUsage routeUsage = routeAndUsage.routeUsage;

			if (activeRoute == null) {
				Log.e(TAG, "No active route found for destination " + finalDestinationAddressName + ". Please initiate route discovery.");
				callback.accept(null, false);
				return;
			}
			if (routeUsage == null) {
				Log.e(TAG, "Impossible to find the RouteUsage for destination " + finalDestinationAddressName + ". Please initiate route discovery.");
				callback.accept(null, false);
				return;
			}

			// 3. Resolve the next hop node for this route
			Node nextHopNode = nodeRepository.findNodeSync(activeRoute.getNextHopLocalId());
			if (nextHopNode == null) {
				Log.e(TAG, "Next hop node for route " + activeRoute.getDiscoveryUuid() + " not found. Route might be stale/invalid.");
				routeEntryDao.delete(activeRoute); // Invalidate the bad route
				callback.accept(null, false);
				return;
			}
			String nextHopAddressName = nextHopNode.getAddressName();

			// 4. Create the end-to-end encrypted RoutedMessageBody
			// This part is encrypted for the final destination
			RoutedMessageBody routedMessageBody = RoutedMessageBody.newBuilder()
					.setInternalMessageBody(internalNearbyMessageBody)
					.build();

			CiphertextMessage encryptedRoutedMessageBody;
			try {
				// Encrypt RoutedMessageBody with final destination's public key (E2E encryption)
				SignalProtocolAddress finalDestinationAddress = getAddress(finalDestinationAddressName);
				encryptedRoutedMessageBody = signalManager.encryptMessage(finalDestinationAddress, routedMessageBody.toByteArray());
			} catch (Exception e) {
				Log.e(TAG, "Failed to E2E encrypt RoutedMessageBody for " + finalDestinationAddressName + ": " + e.getMessage(), e);
				callback.accept(null, false);
				return;
			}

			// 5. Create the hop-by-hop RoutedMessage
			// This contains routing info + the E2E encrypted payload
			RoutedMessage routedMessage = RoutedMessage.newBuilder()
					.setFinalDestinationNodeId(finalDestinationAddressName)
					.setRouteUuid(activeRoute.getDiscoveryUuid())
					.setRouteUsageUuid(routeUsage.getUsageRequestUuid())
					.setEncryptedRoutedMessageBody(ByteString.copyFrom(encryptedRoutedMessageBody.serialize()))
					.setOriginalSenderNodeId(originalSenderAddressName)
					.build();

			// 6. Encapsulate RoutedMessage into an OUTER NearbyMessageBody of type ROUTED_MESSAGE
			NearbyMessageBody outerNearbyMessageBody = NearbyMessageBody.newBuilder()
					.setMessageType(NearbyMessageBody.MessageType.ROUTED_MESSAGE)
					.setEncryptedData(routedMessage.toByteString())
					.build();

			// 7. Send the OUTER NearbyMessageBody to the next hop
			// This part will be encrypted hop-by-hop by NearbySignalMessenger
			sendRoutedMessageToNextHop(nextHopAddressName, outerNearbyMessageBody, callback);

			Log.d(TAG, "Message for " + finalDestinationAddressName + " sent via route " + activeRoute.getDiscoveryUuid() + " to next hop " + nextHopAddressName);

			// 8. Update RouteUsage timestamp for the used route
			routeUsage.setLastUsedTimestamp(System.currentTimeMillis());
			routeUsageDao.update(routeUsage);
		});
	}

	/**
	 * Handles an incoming {@link RoutedMessage}, processing it either as the final destination
	 * or as an intermediate node for forwarding.
	 * <p>
	 * If the current node is the final destination, it decrypts the end-to-end encrypted
	 * payload within the {@code incomingRoutedMessage}, extracts the original sender's ID and
	 * the actual message content, and then dispatches it to {@link NearbyManager} for further
	 * application-level processing.
	 * <p>
	 * If the current node is an intermediate hop, it identifies the next hop for the
	 * {@code finalDestinationNodeId} within the {@code incomingRoutedMessage} and
	 * re-encapsulates the message for forwarding to that next hop. The end-to-end
	 * encrypted payload remains untouched by intermediate nodes if the payload is already
	 * set, but altered by defining the payload ID else.
	 * <p>
	 * This method also updates the {@link RouteUsage} timestamp
	 * for the route being utilized.
	 *
	 * @param senderAddressName     The {@code SignalProtocolAddress.name} of the node that sent this
	 *                              {@code RoutedMessage} to the current node (i.e., the previous hop).
	 * @param incomingRoutedMessage The deserialized {@link RoutedMessage} received, containing
	 *                              routing information and the end-to-end encrypted payload.
	 * @param localHostAddressName  The {@code SignalProtocolAddress.name} of the current local node.
	 * @param payloadId             The ID of the original Nearby Connections payload that carried this message.
	 *                              Used primarily for logging and tracking.
	 */
	public void handleIncomingRoutedMessage(
			@NonNull String senderAddressName, // The previous hop
			@NonNull RoutedMessage incomingRoutedMessage, // The deserialized RoutedMessage
			@NonNull String localHostAddressName,
			long payloadId
	) {
		executor.execute(() -> {
			String finalDestinationAddressName = incomingRoutedMessage.getFinalDestinationNodeId();
			String routeUuid = incomingRoutedMessage.getRouteUuid();

			Log.d(TAG, "Handling incoming RoutedMessage for final destination: " + finalDestinationAddressName +
					" via route UUID: " + routeUuid + " from previous hop: " + senderAddressName);

			// 1. Check if current node is the final destination
			if (finalDestinationAddressName.equals(localHostAddressName)) {
				Log.d(TAG, "Current node is the final destination for RoutedMessage with UUID: " + routeUuid);

				// a. Decrypt the end-to-end encrypted payload (RoutedMessageBody)
				RoutedMessageBody decryptedRoutedMessageBody;
				SignalProtocolAddress originalSenderSignalAddress = getAddress(incomingRoutedMessage.getOriginalSenderNodeId());
				CiphertextMessage ciphertextMessage;
				byte[] cipherData = incomingRoutedMessage.getEncryptedRoutedMessageBody().toByteArray();
				try {
					try {
						ciphertextMessage = new SignalMessage(cipherData);
					} catch (Exception ignored) {
						/*
						 * Instruction in the try clause will throw exception if the message
						 * is the first encrypted through devices
						 */
						ciphertextMessage = new PreKeySignalMessage(cipherData);
					}
					byte[] decryptedBytes = signalManager.decryptMessage(originalSenderSignalAddress, ciphertextMessage);
					decryptedRoutedMessageBody = RoutedMessageBody.parseFrom(decryptedBytes);
				} catch (Exception e) {
					Log.e(TAG, "Failed to E2E decrypt RoutedMessageBody for UUID " + routeUuid + ": " + e.getMessage(), e);
					return;
				}

				// b. Extract original sender and the internal NearbyMessageBody
				String originalSenderNodeId = incomingRoutedMessage.getOriginalSenderNodeId();
				NearbyMessageBody internalNearbyMessageBody = decryptedRoutedMessageBody.getInternalMessageBody();
				Log.d(TAG, "Message for " + finalDestinationAddressName + " from original sender " + originalSenderNodeId + " received.");
				// c. Deliver the internalNearbyMessageBody to the application layer via NearbyManager
				nearbyManager.onRoutedMessageReceived(originalSenderNodeId, internalNearbyMessageBody,
						incomingRoutedMessage.hasPayloadId() ? incomingRoutedMessage.getPayloadId() : payloadId);
			} else {
				// 2. Current node is an intermediate node, forward the message
				Log.d(TAG, "Current node is an intermediate node for RoutedMessage to " + finalDestinationAddressName);

				// a. Find the active route from current node to final destination
				RouteAndUsage routeAndUsage = getIfExistRouteAndUsageFor(finalDestinationAddressName);
				if (routeAndUsage == null) {
					Log.e(TAG, "No active pair route-usage found for destination " + finalDestinationAddressName + ". Please initiate route discovery.");
					return;
				}
				RouteEntry activeRoute = routeAndUsage.routeEntry;
				RouteUsage routeUsage = routeAndUsage.routeUsage;

				if (activeRoute == null) {
					Log.e(TAG, "No active route from intermediate node to " + finalDestinationAddressName + ". Cannot forward RoutedMessage.");
					return;
				}
				if (routeUsage == null) {
					Log.w(TAG, "Unable to locate the route usage for request ID=" + incomingRoutedMessage.getRouteUuid() + " from " + senderAddressName);
					return;
				}

				// b. Resolve the next hop
				Node nextHopNode = nodeRepository.findNodeSync(activeRoute.getNextHopLocalId());
				if (nextHopNode == null) {
					Log.e(TAG, "Next hop node for route " + activeRoute.getDiscoveryUuid() + " not found on intermediate node. Route might be stale/invalid.");
					routeEntryDao.delete(activeRoute);
					return;
				}
				String nextHopAddressName = nextHopNode.getAddressName();
				RoutedMessage routedMessage;
				if (incomingRoutedMessage.hasPayloadId() && incomingRoutedMessage.getPayloadId() != 0L) {
					routedMessage = incomingRoutedMessage; // Re-use the existing RoutedMessage bytes
				} else {
					routedMessage = incomingRoutedMessage.toBuilder().setPayloadId(payloadId).build();
				}

				// c. Create an OUTER NearbyMessageBody with the SAME incoming RoutedMessage
				// The `encrypted_routed_message_body` is left untouched, as it's E2E encrypted.
				NearbyMessageBody outerNearbyMessageBody = NearbyMessageBody.newBuilder()
						.setMessageType(NearbyMessageBody.MessageType.ROUTED_MESSAGE)
						.setEncryptedData(routedMessage.toByteString())
						.build();

				// d. Send this OUTER NearbyMessageBody to the next hop
				sendRoutedMessageToNextHop(nextHopAddressName, outerNearbyMessageBody, null);

				Log.d(TAG, "RoutedMessage for " + finalDestinationAddressName + " forwarded to " + nextHopAddressName);
			}
		});
	}

	// Helper class
	public static class RouteAndUsage {
		public RouteEntry routeEntry;
		public RouteUsage routeUsage;

		public RouteAndUsage(RouteEntry routeEntry, RouteUsage routeUsage) {
			this.routeEntry = routeEntry;
			this.routeUsage = routeUsage;
		}
	}
}
