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
import org.sedo.satmesh.model.rt.RouteUsageDao;
import org.sedo.satmesh.proto.NearbyMessage;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.RouteRequestMessage;
import org.sedo.satmesh.proto.RouteResponseMessage;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeRepository.NodeCallback;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.CiphertextMessage;

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
	 * This method runs on caller thread.
	 *
	 * @param recipientAddressName The SignalProtocolAddress.name of the recipient.
	 * @param messageBody          The RouteRequestMessage to send.
	 */
	private void sendRouteRequestMessage(@NonNull String recipientAddressName, @NonNull RouteRequestMessage messageBody) {
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
	 *
	 * @param endpointId        The Nearby endpoint ID from which the message was received.
	 * @param senderAddressName The SignalProtocolAddress.name of the sender node (previous hop on the return path).
	 * @param routeResponse     The parsed RouteResponseMessage Protobuf object.
	 * @param payloadId         The Nearby Payload ID.
	 */
	public void handleIncomingRouteResponse(
			@NonNull String endpointId,
			@NonNull String senderAddressName,
			@NonNull RouteResponseMessage routeResponse,
			long payloadId) {
		executor.execute(() -> {
			Log.d(TAG, "Handling incoming RouteResponseMessage from " + senderAddressName +
					" for Request UUID: " + routeResponse.getRequestUuid() +
					", Status: " + routeResponse.getStatus() +
					", Payload ID: " + payloadId);

			// TODO: Implement the detailed logic for RouteResponseMessage handling:
			// 1. Resolve senderAddressName to senderNodeId (local ID).
			// 2. Retrieve corresponding RouteRequestEntry using request_uuid.
			// 3. Update BroadcastStatusEntry for the specific sender.
			// 4. Based on status:
			//    - If ROUTE_FOUND:
			//      - Store/update RouteEntry and RouteUsage.
			//      - Cancel parallel requests for this UUID/destination.
			//      - Relay response to previous_hop (from RouteRequestEntry).
			//      - If current node is the original source, signal route establishment.
			//    - If negative status (NO_ROUTE_FOUND, TTL_EXPIRED, etc.):
			//      - Remove BroadcastStatusEntry for this specific neighbor.
			//      - Check if other broadcasts for this UUID are still pending.
			//      - If no other pending broadcasts for this request_uuid, relay failure to previous_hop.
			//      - If current node is original source and no route found, notify user.

			Log.d(TAG, "RouteResponseMessage processing stub completed for " + routeResponse.getRequestUuid());
		});
	}
}
