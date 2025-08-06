package org.sedo.satmesh.nearby;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.nearby.connection.Payload;
import com.google.protobuf.ByteString;

import org.jetbrains.annotations.NotNull;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.rt.BroadcastStatusEntry;
import org.sedo.satmesh.model.rt.RouteEntry;
import org.sedo.satmesh.model.rt.RouteRequestEntry;
import org.sedo.satmesh.model.rt.RouteUsage;
import org.sedo.satmesh.model.rt.RouteUsageBacktracking;
import org.sedo.satmesh.nearby.data.RouteRepository;
import org.sedo.satmesh.nearby.data.RouteWithUsage;
import org.sedo.satmesh.nearby.data.SelectionCallback;
import org.sedo.satmesh.nearby.data.TransmissionCallback;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.RouteDestroyMessage;
import org.sedo.satmesh.proto.RouteRequestMessage;
import org.sedo.satmesh.proto.RouteResponseMessage;
import org.sedo.satmesh.proto.RoutedMessage;
import org.sedo.satmesh.proto.RoutedMessageBody;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeRepository.NodeCallback;
import org.sedo.satmesh.utils.DataLog;
import org.sedo.satmesh.utils.ObjectHolder;
import org.whispersystems.libsignal.protocol.CiphertextMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NearbyRouteManager {

	// Constants
	private static final long ROUTE_MAX_INACTIVITY_MILLIS = 12L * 60 * 60 * 1000; // 12 hours of inactivity before considering a route stale
	private static final int DEFAULT_ROUTE_HOPS = 10; // Default max hops for route discovery
	private static final long DEFAULT_ROUTE_TTL_MILLIS = 5L * 60 * 1000; // 5 minutes TTL for a route request message

	private static final String TAG = "NearbyRouteManager";

	private final NearbyManager nearbyManager;
	private final NodeRepository nodeRepository;
	private final RouteRepository routeRepository;
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
			@NonNull Context context, @NonNull ExecutorService executor) {
		this.nearbyManager = nearbyManager;
		this.nodeRepository = new NodeRepository(context);
		this.routeRepository = new RouteRepository(context);
		this.executor = executor;
	}

	// Helper method for data collection

	/**
	 * Creates a map of routing event parameters, including only non-null values.
	 * This helper method simplifies the creation of the 'params' map for the logRouteEvent function.
	 * It uses predefined static keys from DataLog (e.g., PARAM_SOURCE_NODE_UUID).
	 *
	 * @param destNodeUuid        The UUID of the destination node. Can be null.
	 * @param interactingNodeUuid The UUID of the neighbor involved in the event. Can be null.
	 * @param statusOrReason      The status or reason for the event (e.g., "ROUTE_FOUND", "NO_ROUTE_FOUND"). Can be null.
	 * @param hops                The number of hops (as a String). Can be null.
	 * @return A {@code Map<String, String>} containing only the non-null parameters.
	 */
	private static Map<String, String> params(
			String destNodeUuid, String interactingNodeUuid,
			String statusOrReason, int hops) {

		Map<String, String> map = new HashMap<>();

		// Only add parameters to the map if their values are not null
		if (destNodeUuid != null) {
			map.put(DataLog.PARAM_DEST_NODE_UUID, destNodeUuid);
		}
		if (interactingNodeUuid != null) {
			map.put(DataLog.PARAM_INTERACTING_NODE_UUID, interactingNodeUuid);
		}
		if (statusOrReason != null) {
			map.put(DataLog.PARAM_STATUS_OR_REASON, statusOrReason);
		}
		if (hops >= 0) {
			map.put(DataLog.PARAM_HOPS, String.valueOf(hops));
		}

		return map;
	}

	/**
	 * Helper method to send a RouteRequestMessage.
	 * This method runs on an execution thread.
	 *
	 * @param recipientAddressName The SignalProtocolAddress.name of the recipient.
	 * @param messageBody          The RouteRequestMessage to send.
	 * @param broadcastCallback    Used to notice caller of success or failure of the
	 *                             broadcast. This is mainly for processing GLOBAL
	 *                             controls/instructions on broadcasts, except log
	 *                             of the broadcast event. This is to help log of
	 *                             request init first, in the corresponding case.
	 */
	private void sendRouteRequestMessage(
			@NonNull String recipientAddressName, @NonNull RouteRequestMessage messageBody,
			@NonNull BroadcastRequestCallback broadcastCallback) {
		try {
			Log.d(TAG, "Sending RouteRequestMessage to " + recipientAddressName + " for UUID: " + messageBody.getUuid());
			nodeRepository.findOrCreateNodeAsync(recipientAddressName, new NodeCallback() {
				@Override
				public void onNodeReady(@NonNull Node node) {
					NearbyMessageBody nearbyMessageBody = NearbyMessageBody.newBuilder()
							.setMessageType(NearbyMessageBody.MessageType.ROUTE_DISCOVERY_REQ)
							.setBinaryData(messageBody.toByteString())
							.build();

					// NearbyManager handles encryption and payload sending
					// The callback needs to handle the payload ID for tracking
					nearbyManager.encryptAndSendInternal( // endpoint ID will be automatically evaluated
							null, recipientAddressName, nearbyMessageBody,
							new TransmissionCallback() {
								@Override
								public void onSuccess(@NonNull Payload payload) {
									Log.d(TAG, "RouteRequestMessage payload " + payload.getId() + " sent successfully to " + recipientAddressName);
									// Ensure the broadcast status is recorded for tracking
									BroadcastStatusEntry broadcastStatus = new BroadcastStatusEntry(messageBody.getUuid(), node.getId());
									broadcastStatus.setPendingResponseInProgress(false); // Default to false
									routeRepository.insertBroadcastStatus(broadcastStatus, onSuccess -> {
										if (onSuccess) {
											Log.d(TAG, "BroadcastStatusEntry created for request " + messageBody.getUuid() + " to neighbor " + node.getId());
											broadcastCallback.onSuccess(recipientAddressName);
										} else {
											Log.w(TAG, "Failed to persist the broadcast status!");
											broadcastCallback.onFailure(recipientAddressName);
										}
									});
								}

								@Override
								public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
									Log.e(TAG, "Failed to send RouteRequestMessage payload " +
											(payload != null ? payload.getId() : "N/A") + " to " + recipientAddressName);
									broadcastCallback.onFailure(recipientAddressName);
								}
							});
				}

				@Override
				public void onError(@NonNull String errorMessage) {
					Log.w(TAG, "Failed to persist/retrieve the node: " + errorMessage);
					broadcastCallback.onFailure(recipientAddressName);
				}
			});
		} catch (Exception e) {
			Log.e(TAG, "Error building or sending RouteRequestMessage to " + recipientAddressName + ": " + e.getMessage(), e);
			broadcastCallback.onFailure(recipientAddressName);
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
	 * @param successCountCallback     The consumer of number of success of broadcasts
	 */
	private void broadcastRouteRequestToNeighbors(
			@NonNull RouteRequestMessage routeRequestMessage, @Nullable String excludeSenderAddressName,
			@NonNull Consumer<Integer> successCountCallback) {
		Log.d(TAG, "Broadcasting RouteRequest with UUID: " + routeRequestMessage.getUuid() +
				" to all neighbors (excluding: " + (excludeSenderAddressName != null ? excludeSenderAddressName : "none") + ")");

		List<String> connectedNeighbors = nearbyManager.getConnectedEndpointsAddressNames();
		if (excludeSenderAddressName != null) {
			connectedNeighbors.removeIf(s -> s.equals(excludeSenderAddressName));
		}
		if (connectedNeighbors.isEmpty()) {
			Log.d(TAG, "No connected neighbors to broadcast RouteRequest to.");
			// No action needed here, the caller is responsible for handling lack of neighbors
			successCountCallback.accept(0);
			return;
		}

		// Optimisation
		String destinationAddressName = routeRequestMessage.getDestinationNodeId();
		List<String> targets = connectedNeighbors.contains(destinationAddressName) ?
				Collections.singletonList(destinationAddressName) : connectedNeighbors;

		int total = targets.size();
		Map<String, Boolean> statuses = new ConcurrentHashMap<>();
		ObjectHolder<Boolean> hasOneSuccessAtLeast = new ObjectHolder<>();
		hasOneSuccessAtLeast.post(false);
		BiConsumer<String, Boolean> onItemResult = (neighborAddressName, result) -> {
			statuses.put(neighborAddressName, result);
			if (statuses.size() == total) {
				// All broadcasts have been processed
				long successes = statuses.entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue())).count();
				Log.d(TAG, "Attempt " + total + " broadcast and have " + successes + " successes.");
				successCountCallback.accept(Long.valueOf(successes).intValue());
			}
			// Else wait for remaining results
		};

		for (String neighborAddressName : targets) {
			/*
			 * Call the helper method to send the message to this specific neighbor
			 * The `sendRouteRequestMessage` method itself will handle the DB insertion
			 * for BroadcastStatusEntry upon successful payload send.
			 */
			sendRouteRequestMessage(neighborAddressName, routeRequestMessage, new BroadcastRequestCallback() {
				@Override
				public void onSuccess(@NotNull String neighborAddressName) {
					if (!hasOneSuccessAtLeast.getValue()) {
						// First success
						if (excludeSenderAddressName == null) {
							// We are on node that is initiating a route request
							DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_REQ_INIT,
									routeRequestMessage.getUuid(),
									params(routeRequestMessage.getDestinationNodeId(), null, null, routeRequestMessage.getRemainingHops()));
						}
						// Else we are on intermediate node that is relaying the route request
					}
					hasOneSuccessAtLeast.post(true);
					DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_REQ_RELAYED, routeRequestMessage.getUuid(),
							params(routeRequestMessage.getDestinationNodeId(), neighborAddressName, null, routeRequestMessage.getRemainingHops()));
					onItemResult.accept(neighborAddressName, true);
				}

				@Override
				public void onFailure(@NotNull String neighborAddressName) {
					onItemResult.accept(neighborAddressName, false);
				}
			});
		}
	}

	/**
	 * Initiates a new route discovery process to find a path to a specified destination node.
	 * This method is called by a higher-level component
	 * when a route is needed for a specific destination.
	 * This method first checks for an existing usable route. If no such route is found,
	 * it generates a new route request UUID, stores the request locally, and broadcasts it to neighbors.
	 * This method runs on the executor thread.
	 *
	 * @param destinationNodeAddressName   The SignalProtocolAddress.name of the node to which a route is desired.
	 * @param onRouteFoundCallback         A callback executed if an existing, usable route is found.
	 *                                     The {@code RouteWithUsage} object representing the found route is passed to the callback.
	 * @param onDiscoveryInitiatedCallback A callback executed to inform the caller about the status of the new discovery.
	 *                                     A {@code true} value indicates the request was broadcasted to at least one neighbor.
	 *                                     A {@code false} value indicates the discovery could not be initiated (e.g., no neighbors).
	 *                                     This callback is optional.
	 * @param localHostAddress             The SignalProtocolAddress.name of the local host Node.
	 */
	public void discoverRouteIfNeeded(
			@NonNull String destinationNodeAddressName, @NonNull Consumer<RouteWithUsage> onRouteFoundCallback,
			@Nullable Consumer<Boolean> onDiscoveryInitiatedCallback, @NonNull String localHostAddress) {
		Consumer<Boolean> discoveryCallback = result -> {
			if (onDiscoveryInitiatedCallback != null) {
				onDiscoveryInitiatedCallback.accept(result);
			}
		};
		try {
			Log.d(TAG, "Entering route discovery initialization for destination: " + destinationNodeAddressName);

			// 1. Resolve destinationNodeAddressName to destinationNodeLocalId
			nodeRepository.findOrCreateNodeAsync(destinationNodeAddressName, new NodeCallback() {
				@Override
				public void onNodeReady(@NonNull Node node) {
					executor.execute(() -> {
						// 2. Check for an existing, active, and recently used route to the destination
						RouteWithUsage routeWithUsage = routeRepository.findMostRecentRouteByDestinationSync(node.getId());
						if (routeWithUsage != null) {
							Log.d(TAG, "Existing active route found for " + destinationNodeAddressName + ". Using it.");
							// Notify the higher layer that a route is available
							onRouteFoundCallback.accept(routeWithUsage);
							return;
						} else {
							Log.d(TAG, "No existing active route found for " + destinationNodeAddressName + ". Initiating new discovery.");
						}

						// If we reach here, so no route was found, or the existing one was stale.
						// Proceed with new route discovery.

						// 3. Generate a new UUID for the route request
						String requestUuid = UUID.randomUUID().toString();

						// 4. Store the initial RouteRequestEntry
						RouteRequestEntry newRequestEntry = new RouteRequestEntry(requestUuid);
						newRequestEntry.setDestinationNodeLocalId(node.getId());
						newRequestEntry.setPreviousHopLocalId(null); // Null because this is the original source node
						routeRepository.insertRouteRequest(newRequestEntry, aBoolean -> {
							if (aBoolean) {
								Log.d(TAG, "New RouteRequestEntry created for UUID: " + requestUuid);
								// 5. Construct the RouteRequestMessage Protobuf
								RouteRequestMessage routeRequest = RouteRequestMessage.newBuilder()
										.setUuid(requestUuid)
										.setDestinationNodeId(destinationNodeAddressName)
										.setRemainingHops(DEFAULT_ROUTE_HOPS)
										.setMaxTtl(System.currentTimeMillis() + DEFAULT_ROUTE_TTL_MILLIS)
										.setInitiatorNodeId(localHostAddress)
										.build();

								// 6. Broadcast the request to all neighbors (no exclusion for initial broadcast)
								broadcastRouteRequestToNeighbors(routeRequest, null, sentToNeighborsCount -> {
									// Inform caller about the discovery initiation status
									discoveryCallback.accept(sentToNeighborsCount > 0);

									if (sentToNeighborsCount == 0) {
										Log.w(TAG, "Route request " + requestUuid + " initiated, but no neighbors to broadcast to. Discovery might fail.");
										routeRepository.deleteRouteRequestByRequestUuid(requestUuid);
									}
								});
							} else {
								Log.w(TAG, "Failed to create new RouteRequestEntry for UUID: " + requestUuid);
								discoveryCallback.accept(false);
							}
						});
					});
				}

				@Override
				public void onError(@NonNull String errorMessage) {
					Log.e(TAG, "Destination node " + destinationNodeAddressName + " not found in local repository. Cannot initiate route discovery.");
					// Inform caller that discovery could not be initiated
					discoveryCallback.accept(false);
				}
			});
		} catch (Exception e) {
			Log.e(TAG, "Unable to initiate route discovery", e);
			discoveryCallback.accept(false);
		}
	}

	/**
	 * Helper method to send a RouteResponseMessage.
	 * This method runs on caller thread.
	 *
	 * @param recipientAddressName The SignalProtocolAddress.name of the recipient.
	 * @param messageBody          The RouteResponseMessage to send.
	 * @param sendingCallback      A callback to be invoked with the sending operation result.
	 */
	private void sendRouteResponseMessage(
			@NonNull String recipientAddressName, @NonNull RouteResponseMessage messageBody,
			@NonNull TransmissionCallback sendingCallback) {
		try {
			Log.d(TAG, "Sending RouteResponseMessage to " + recipientAddressName + " for UUID: " + messageBody.getRequestUuid() + " with status: " + messageBody.getStatus());
			NearbyMessageBody nearbyMessageBody = NearbyMessageBody.newBuilder()
					.setMessageType(NearbyMessageBody.MessageType.ROUTE_DISCOVERY_RESP)
					.setBinaryData(messageBody.toByteString())
					.build();

			TransmissionCallback sendingCallbackWrapper = new TransmissionCallback() {
				@Override
				public void onSuccess(@NonNull Payload payload) {
					DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_RESP_SENT, messageBody.getRequestUuid(),
							params(null, recipientAddressName, messageBody.getStatus().name(), messageBody.getHopCount()));
					sendingCallback.onSuccess(payload);
				}

				@Override
				public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
					sendingCallback.onFailure(payload, cause);
				}
			};
			nearbyManager.encryptAndSendInternal(null, recipientAddressName, nearbyMessageBody, sendingCallbackWrapper);
		} catch (Exception e) {
			Log.e(TAG, "Error building or sending RouteResponseMessage to " + recipientAddressName + ": " + e.getMessage(), e);
			sendingCallback.onFailure(null, e);
		}
	}

	private void handleIncomingRouteRequestAlreadyInProgress(RouteRequestMessage routeRequest, Node sender, Consumer<Boolean> stopProcessing) {
		executor.execute(() -> {
			RouteRequestEntry existingRequest = routeRepository.findRouteRequestByUuidSync(routeRequest.getUuid());

			if (existingRequest != null) {
				Log.d(TAG, "RouteRequest with UUID " + routeRequest.getUuid() + " already in progress. Sending REQUEST_ALREADY_IN_PROGRESS response.");
				RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
						.setRequestUuid(routeRequest.getUuid())
						.setStatus(RouteResponseMessage.Status.REQUEST_ALREADY_IN_PROGRESS)
						.setHopCount(0)
						.build();

				sendRouteResponseMessage(sender.getAddressName(), responseMessage,
						new TransmissionCallback() {
							@Override
							public void onSuccess(@NonNull Payload payload) {
								DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_RESP_SENT, routeRequest.getUuid(),
										params(routeRequest.getDestinationNodeId(), sender.getAddressName(), responseMessage.getStatus().name(),
												routeRequest.getRemainingHops()));
								Log.d(TAG, "Sent REQUEST_ALREADY_IN_PROGRESS response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
							}

							@Override
							public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
								Log.e(TAG, "Failed to send REQUEST_ALREADY_IN_PROGRESS response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
							}
						});
				stopProcessing.accept(true); // Stop processing, request is redundant.
				return;
			}
			stopProcessing.accept(false);
		});
	}

	private void handleIncomingRouteRequestExpiration(
			RouteRequestMessage routeRequest, Node sender, String localHostAddress, Consumer<Boolean> stopProcessing) {
		executor.execute(() -> {
			long currentTime = System.currentTimeMillis();
			if (routeRequest.getMaxTtl() < currentTime) {
				Log.d(TAG, "RouteRequest " + routeRequest.getUuid() + " has expired (TTL reached). Sending TTL_EXPIRED response.");
				RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
						.setRequestUuid(routeRequest.getUuid())
						.setStatus(RouteResponseMessage.Status.TTL_EXPIRED)
						.setHopCount(0)
						.build();
				sendRouteResponseMessage(sender.getAddressName(), responseMessage,
						new TransmissionCallback() {
							@Override
							public void onSuccess(@NonNull Payload payload) {
								DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_RESP_SENT, routeRequest.getUuid(),
										params(routeRequest.getDestinationNodeId(), sender.getAddressName(), responseMessage.getStatus().name(),
												routeRequest.getRemainingHops()));
								Log.d(TAG, "Sent TTL_EXPIRED response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
							}

							@Override
							public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
								Log.e(TAG, "Failed to send TTL_EXPIRED response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
							}
						});
				stopProcessing.accept(true);
				return; // Stop processing.
			}

			boolean isHostTheDestination = localHostAddress.equals(routeRequest.getDestinationNodeId());
			if (routeRequest.getRemainingHops() <= 0 && !isHostTheDestination) {
				Log.d(TAG, "RouteRequest " + routeRequest.getUuid() + " has reached max hops. Sending MAX_HOPS_REACHED response.");
				RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
						.setRequestUuid(routeRequest.getUuid())
						.setStatus(RouteResponseMessage.Status.MAX_HOPS_REACHED)
						.setHopCount(0)
						.build();
				sendRouteResponseMessage(sender.getAddressName(), responseMessage,
						new TransmissionCallback() {
							@Override
							public void onSuccess(@NonNull Payload payload) {
								DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_RESP_SENT, routeRequest.getUuid(),
										params(routeRequest.getDestinationNodeId(), sender.getAddressName(), responseMessage.getStatus().name(),
												routeRequest.getRemainingHops()));
								Log.d(TAG, "Sent MAX_HOPS_REACHED response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
							}

							@Override
							public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
								Log.e(TAG, "Failed to send MAX_HOPS_REACHED response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
							}
						});
				stopProcessing.accept(true);
				return; // Stop processing.
			}
			stopProcessing.accept(false);
		});
	}

	private void handleIncomingRouteRequestIfDestination(RouteRequestMessage routeRequest, Node sender, String localHostAddress, Consumer<Boolean> stopProcessing) {
		boolean isHostTheDestination = localHostAddress.equals(routeRequest.getDestinationNodeId());
		if (isHostTheDestination) {
			Log.d(TAG, "Current node is the destination for RouteRequest " + routeRequest.getUuid() + ". Sending ROUTE_FOUND response.");

			// Send ROUTE_FOUND response back to the previous hop (sender of this request)
			RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
					.setRequestUuid(routeRequest.getUuid())
					.setStatus(RouteResponseMessage.Status.ROUTE_FOUND)
					.setHopCount(0)
					.build();
			sendRouteResponseMessage(sender.getAddressName(), responseMessage,
					new TransmissionCallback() {
						@Override
						public void onSuccess(@NonNull Payload payload) {
							DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_RESP_SENT, routeRequest.getUuid(),
									params(routeRequest.getDestinationNodeId(), sender.getAddressName(), responseMessage.getStatus().name(),
											routeRequest.getRemainingHops()));
							Log.d(TAG, "Sent ROUTE_FOUND response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
							nodeRepository.findOrCreateNodeAsync(routeRequest.getInitiatorNodeId(), new NodeCallback() {
								@Override
								public void onNodeReady(@NonNull Node initiatorNode) {
									// Store the route entry in the database
									RouteEntry routeEntry = new RouteEntry();
									routeEntry.setDiscoveryUuid(routeRequest.getUuid());
									routeEntry.setDestinationNodeLocalId(null); // Cause the host if the destination
									routeEntry.setHopCount(0);
									routeEntry.setNextHopLocalId(null);
									routeEntry.setPreviousHopLocalId(sender.getId());
									routeEntry.setLastUseTimestamp(System.currentTimeMillis());
									routeRepository.insertRouteEntry(routeEntry, onSuccess -> {
										if (onSuccess) {
											Log.d(TAG, "RouteEntry stored for UUID: " + routeRequest.getUuid());
											RouteUsage usage = new RouteUsage(routeRequest.getUuid());
											usage.setRouteEntryDiscoveryUuid(routeRequest.getUuid());
											usage.setPreviousHopLocalId(sender.getId());
											routeRepository.insertRouteUsage(usage, ok -> {
												if (ok) {
													Log.d(TAG, "RouteUsage stored for UUID: " + routeRequest.getUuid());
													// Now persist the backtracking path
													RouteUsageBacktracking backtracking = new RouteUsageBacktracking(routeRequest.getUuid(), initiatorNode.getId());
													routeRepository.insertRouteUsageBacktracking(backtracking, yes -> {
														if (yes) {
															Log.d(TAG, "RouteUsageBacktracking stored for UUID: " + routeRequest.getUuid());
														} else {
															Log.e(TAG, "Failed to store RouteUsageBacktracking for UUID: " + routeRequest.getUuid());
														}
													});
												} else {
													Log.e(TAG, "Failed to store RouteUsage for UUID: " + routeRequest.getUuid());
												}
											});
										} else {
											Log.w(TAG, "Impossible to persist the RouteEntry for UUID: " + routeRequest.getUuid() + ".");
										}
									});
								}

								@Override
								public void onError(@NonNull String errorMessage) {
									Log.e(TAG, "Failed to store RouteEntry for UUID: " + routeRequest.getUuid() + ". Error: " + errorMessage);
								}
							});
						}

						@Override
						public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
							Log.e(TAG, "Failed to send ROUTE_FOUND response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
						}
					}
			);
		}
		stopProcessing.accept(isHostTheDestination);
	}

	private void handleIncomingRouteRequestForRouteReuse(RouteRequestMessage routeRequest, Node sender, Consumer<Boolean> stopProcessing) {
		executor.execute(() -> {
			RouteWithUsage routeWithUsage = getIfExistRouteAndUsageFor(routeRequest.getDestinationNodeId());
			if (routeWithUsage != null) {
				Log.d(TAG, "Route already exists for intermediate node.");
				if (routeRequest.getRemainingHops() >= routeWithUsage.routeEntry.getHopCount()) {
					Log.d(TAG, "Route is usable. Sending ROUTE_FOUND response.");
					RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
							.setRequestUuid(routeRequest.getUuid())
							.setStatus(RouteResponseMessage.Status.ROUTE_FOUND)
							.setHopCount(routeWithUsage.routeEntry.getHopCount()) // This is very important
							.build();
					sendRouteResponseMessage(sender.getAddressName(), responseMessage, new TransmissionCallback() {
						@Override
						public void onSuccess(@NonNull Payload payload) {
							// Save usages
							nodeRepository.findOrCreateNodeAsync(routeRequest.getInitiatorNodeId(), new NodeCallback() {
								@Override
								public void onNodeReady(@NonNull Node initiator) {
									RouteUsage usage = new RouteUsage(routeRequest.getUuid());
									usage.setPreviousHopLocalId(sender.getId()); // This is very important for backtracking
									usage.setRouteEntryDiscoveryUuid(routeWithUsage.routeEntry.getDiscoveryUuid());
									routeRepository.insertRouteUsage(usage, ok -> {
										if (ok) {
											Log.d(TAG, "RouteUsage stored for usage  UUID: " + routeRequest.getUuid());
											RouteUsageBacktracking backtracking = new RouteUsageBacktracking(routeRequest.getUuid(), initiator.getId());
											routeRepository.insertRouteUsageBacktracking(backtracking, yes -> {
												if (yes) {
													Log.d(TAG, "RouteUsageBacktracking stored for UUID: " + routeRequest.getUuid());
												} else {
													Log.e(TAG, "Failed to store RouteUsageBacktracking for UUID: " + routeRequest.getUuid());
												}
											});
										} else {
											Log.e(TAG, "Failed to store RouteUsage for usage UUID: " + routeRequest.getUuid());
										}
									});
								}

								@Override
								public void onError(@NonNull String errorMessage) {
									Log.e(TAG, "Failed to store RouteUsage for usage UUID: " + routeRequest.getUuid() + ". Error: " + errorMessage);
								}
							});
						}

						@Override
						public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
							Log.e(TAG, "Failed to send ROUTE_FOUND response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
						}
					});
					stopProcessing.accept(true);
					return;
				} else {
					// Else the existing route is not usable, continue processing.
					Log.d(TAG, "Route is stale. Continuing processing.");
				}
			}
			stopProcessing.accept(false);
		});
	}

	private void handleIncomingRouteRequestRelay(RouteRequestMessage routeRequest, Node sender) {
		// Ensure destination exists
		nodeRepository.findOrCreateNodeAsync(routeRequest.getDestinationNodeId(), new NodeCallback() {
			@Override
			public void onNodeReady(@NonNull Node destination) {
				RouteRequestEntry newRequestEntry = new RouteRequestEntry(routeRequest.getUuid());
				newRequestEntry.setDestinationNodeLocalId(destination.getId());
				newRequestEntry.setPreviousHopLocalId(sender.getId()); // The node that sent us this request
				routeRepository.insertRouteRequest(newRequestEntry, onSuccess -> {
					if (onSuccess) {
						Log.d(TAG, "New RouteRequestEntry created for UUID: " + routeRequest.getUuid());
						// Decrement hops and relay the request to other neighbors
						int newRemainingHops = routeRequest.getRemainingHops() - 1;
						RouteRequestMessage relayedRouteRequest = routeRequest.toBuilder()
								.setRemainingHops(newRemainingHops)
								.build();
						// Broadcast the request to all neighbors, excluding the sender of this request
						broadcastRouteRequestToNeighbors(relayedRouteRequest, sender.getAddressName(),
								sentToNeighborsCount -> {
									if (sentToNeighborsCount == 0) {
										Log.w(TAG, "Route request " + routeRequest.getUuid() + " received, but no other neighbors to relay to. Sending NO_ROUTE_FOUND response back.");
										// If no other neighbors to relay to, this branch of discovery ends here.
										RouteResponseMessage responseMessage = RouteResponseMessage.newBuilder()
												.setRequestUuid(routeRequest.getUuid())
												.setStatus(RouteResponseMessage.Status.NO_ROUTE_FOUND)
												.setHopCount(0) // Initiator of the result
												.build();
										sendRouteResponseMessage(sender.getAddressName(), responseMessage,
												new TransmissionCallback() {
													@Override
													public void onSuccess(@NonNull Payload payload) {
														DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_RESP_SENT, routeRequest.getUuid(),
																params(routeRequest.getDestinationNodeId(), sender.getAddressName(), responseMessage.getStatus().name(),
																		routeRequest.getRemainingHops()));
														/*
														 * using `routeRequest.getRemainingHops()` is not an error
														 * the remaining hops will be match with route init log to
														 * determine the number of hops from source to here.
														 */
														Log.d(TAG, "Sent NO_ROUTE_FOUND response for " + routeRequest.getUuid() + " (no relays) to " + sender.getAddressName());
													}

													@Override
													public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
														Log.e(TAG, "Failed to send NO_ROUTE_FOUND response for " + routeRequest.getUuid() + " to " + sender.getAddressName());
													}
												});
										// Drop the new mapping from database
										routeRepository.deleteRouteRequestByRequestUuid(routeRequest.getUuid());
									} else {
										Log.d(TAG, "Route request " + routeRequest.getUuid() + " relayed to " + sentToNeighborsCount + " neighbors. New hops: " + newRemainingHops);
									}
								});
					} else {
						Log.w(TAG, "Failed to create new RouteRequestEntry for UUID: " + routeRequest.getUuid());
					}
				});
			}

			@Override
			public void onError(@NonNull String errorMessage) {
				Log.e(TAG, "Failed to resolve the destination Node on an intermediate node. Error: " + errorMessage);
			}
		});
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
			@NonNull String senderAddressName, @NonNull RouteRequestMessage routeRequest,
			@NonNull String localHostAddress) {
		Log.d(TAG, "Handling incoming RouteRequestMessage from " + senderAddressName +
				" for UUID: " + routeRequest.getUuid() +
				", Destination: " + routeRequest.getDestinationNodeId() +
				", Remaining Hops: " + routeRequest.getRemainingHops());

		// Implement the detailed logic for RouteRequestMessage handling:
		// This method processes an incoming route request, acting as either an intermediate node or the final destination.
		// 1. Resolve sender's address name to a local Node ID, creating the node if it's new.
		// 2. Check if the RouteRequest UUID already exists in RouteRequestEntry.
		//    - If found, it's a duplicate; send a "REQUEST_ALREADY_IN_PROGRESS" response to the sender.
		// 3. Check the message's TTL (Time-To-Live) and remaining_hops.
		//    - If expired or zero hops, send "TTL_EXPIRED" or "MAX_HOPS_REACHED" response to the sender.
		// 4. Determine if the current node is the intended destination for the route request. If yes:
		//    - A route has been found! Send a "ROUTE_FOUND" response to the sender (previous hop)
		//    - Store a RouteEntry in the database. This is useful for the route backtracking.
		// 5. If the current node is not the destination (it's an intermediate node):
		//    5.a Check if there is an active route to the destination. If yes:			(optimisation)
		//      - Respond with a "ROUTE_FOUND" response to the sender (previous hop).
		//      - Store the RouteUsage and RouteUsageBacktracking entries.
		//    5.b If no active route is found:
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
				handleIncomingRouteRequestAlreadyInProgress(routeRequest, senderNode, stopIfAlready -> {
					if (stopIfAlready) {
						return;
					}
					// 3. Check TTL and remaining_hops.
					handleIncomingRouteRequestExpiration(routeRequest, senderNode, localHostAddress, stopIfExpired -> {
						if (stopIfExpired) {
							return;
						}
						// 4. Check if current node is the destination.
						handleIncomingRouteRequestIfDestination(routeRequest, senderNode, localHostAddress, stopIfDestination -> {
							if (stopIfDestination) {
								return;
							}
							// If we reach here, this is an intermediate node
							// 5.a
							handleIncomingRouteRequestForRouteReuse(routeRequest, senderNode, stopIfReuse -> {
								if (stopIfReuse) {
									return;
								}
								// 5.b If we reach here, then the request is valid for relaying
								handleIncomingRouteRequestRelay(routeRequest, senderNode);
							});
						});
					});
				});
			}

			@Override
			public void onError(@NonNull String errorMessage) {
				Log.e(TAG, "Failed to resolve or create sender node " + senderAddressName + ": " + errorMessage + ". Aborting RouteRequest processing.");
			}
		});
	}

	private void handleIncomingRouteResponseRouteFound(
			RouteResponseMessage routeResponse, Node sender, Node destination,
			@Nullable Node previousHop, boolean isOriginalSource) {
		routeRepository.deleteRouteRequestByRequestUuid(routeResponse.getRequestUuid());
		DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.REQUEST_ENTRY_DEL, routeResponse.getRequestUuid(),
				params(destination.getAddressName(), null, routeResponse.getStatus().name(), routeResponse.getHopCount()));
		Log.d(TAG, "Deleted RouteRequestEntry for UUID: " + routeResponse.getRequestUuid() + " (ROUTE_FOUND)");

		// 4.b.
		RouteEntry newRouteEntry = new RouteEntry();
		newRouteEntry.setDiscoveryUuid(routeResponse.getRequestUuid());
		newRouteEntry.setDestinationNodeLocalId(destination.getId());
		newRouteEntry.setNextHopLocalId(sender.getId());
		if (isOriginalSource) {
			newRouteEntry.setHopCount(routeResponse.getHopCount());
		} else {
			newRouteEntry.setHopCount(routeResponse.getHopCount() + 1);
		}
		if (previousHop != null) {
			newRouteEntry.setPreviousHopLocalId(previousHop.getId());
		}
		newRouteEntry.setLastUseTimestamp(System.currentTimeMillis());
		routeRepository.insertRouteEntry(newRouteEntry, onSuccess -> {
			if (onSuccess) {
				Log.d(TAG, "RouteEntry stored for UUID: " + routeResponse.getRequestUuid());
				DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_ENTRY_ADD, routeResponse.getRequestUuid(),
						params(destination.getAddressName(), null, routeResponse.getStatus().name(),
								routeResponse.getHopCount()));
				routeRepository.dropBroadcastStatusesByRequestUuid(routeResponse.getRequestUuid());
				DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.BROADCAST_STATUS_DEL, routeResponse.getRequestUuid(),
						params(null, null, routeResponse.getStatus().name(), routeResponse.getHopCount()));
				Log.d(TAG, "Deleted all BroadcastStatusEntries for UUID: " + routeResponse.getRequestUuid() + " (ROUTE_FOUND)");
				if (isOriginalSource) {
					// The route is established. Use a dedicated method NearbyManager.onRouteFound
					nearbyManager.onRouteFound(destination.getAddressName(), newRouteEntry);
					DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_FOUND, routeResponse.getRequestUuid(),
							params(null, null, routeResponse.getStatus().name(), routeResponse.getHopCount()));
					Log.d(TAG, "Notified NearbyManager.onRouteFound for UUID " + routeResponse.getRequestUuid() + " to " + destination.getAddressName());
				} else {
					// 4.f.
					if (previousHop == null) {
						Log.e(TAG, "Failed to retrieve previous hop node for forwarding ROUTE_FOUND response for UUID " + routeResponse.getRequestUuid());
						return;
					}
					RouteResponseMessage nextRouteResponse = routeResponse.toBuilder()
							.setHopCount(routeResponse.getHopCount() + 1)
							.build();
					sendRouteResponseMessage(previousHop.getAddressName(), nextRouteResponse, TransmissionCallback.NULL_CALLBACK);
				}
			} else {
				Log.w(TAG, "Failed to store RouteEntry for UUID: " + routeResponse.getRequestUuid() + ".");
			}
		});
	}

	/**
	 * Internal helper function for common error/completion sequences.
	 * This function simplifies the logic for handling various failure scenarios
	 * where a response needs to be forwarded or the source needs to be notified.
	 */
	private void finalizeHandlingRouteNotFound(
			@NonNull RouteResponseMessage routeResponse, @NonNull Node destination,
			@Nullable Node previousHop, boolean isOriginalSource) {
		routeRepository.dropBroadcastStatusesByRequestUuid(
				routeResponse.getRequestUuid(), deletedCount -> {
					Log.d(TAG, "Deleted " + deletedCount + " BroadcastStatusEntries for UUID: " + routeResponse.getRequestUuid());
					if (deletedCount != null && deletedCount != 0) {
						DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.BROADCAST_STATUS_DEL, routeResponse.getRequestUuid(),
								params(null, null, routeResponse.getStatus().name(), routeResponse.getHopCount()));
					}
				});
		routeRepository.deleteRouteRequestByRequestUuid(
				routeResponse.getRequestUuid(), deletedCount -> {
					Log.d(TAG, "Deleted " + deletedCount + " RouteRequestEntries for UUID: " + routeResponse.getRequestUuid());
					if (deletedCount != null && deletedCount != 0) {
						DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.REQUEST_ENTRY_DEL, routeResponse.getRequestUuid(),
								params(null, null, routeResponse.getStatus().name(), routeResponse.getHopCount()));
					}
				});

		if (isOriginalSource) {
			// Notify the higher layer that the route could not be found.
			nearbyManager.onRouteNotFound(routeResponse.getRequestUuid(), destination.getAddressName(), routeResponse.getStatus());
			Log.d(TAG, "Notified NearbyManager.onRouteNotFound for UUID " + routeResponse.getRequestUuid() + " with status: " + routeResponse.getStatus());
			DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_FAILED, routeResponse.getRequestUuid(),
					params(null, null, routeResponse.getStatus().name(), routeResponse.getHopCount()));
		} else {
			if (previousHop == null) {
				Log.e(TAG, "Failed to retrieve previous hop node for forwarding response for UUID " + routeResponse.getRequestUuid());
				return;
			}
			RouteResponseMessage responseToForward = routeResponse.toBuilder()
					.setHopCount(routeResponse.getHopCount() + 1)
					.build();
			sendRouteResponseMessage(previousHop.getAddressName(), responseToForward, TransmissionCallback.NULL_CALLBACK);
		}
	}

	private void handleIncomingRouteResponseAlreadyInProgress(
			RouteResponseMessage routeResponse, BroadcastStatusEntry broadcastStatus, Node sender,
			Node destination, @Nullable Node previousHop, boolean isOriginalSource) {
		broadcastStatus.setPendingResponseInProgress(true); // Mark as processed for this neighbor
		routeRepository.updateBroadcastStatus(broadcastStatus, ok -> {
			if (ok) {
				Log.d(TAG, "BroadcastStatusEntry for " + broadcastStatus.getRequestUuid() + " to " + sender.getAddressName() + " updated to isPendingResponseInProgress=true.");

				routeRepository.hasBroadcastStatusInProgressState(broadcastStatus.getRequestUuid(), false, isBroadcastRemaining -> {
					if (!Boolean.TRUE.equals(isBroadcastRemaining)) {
						// All broadcasts have been processed

						if (isOriginalSource) {
							// 5.c.i.
							Log.e(TAG, "Unexpected REQUEST_ALREADY_IN_PROGRESS response for UUID " + broadcastStatus.getRequestUuid() + " at original source. Potential UUID collision.");
						}
						finalizeHandlingRouteNotFound(routeResponse, destination, previousHop, isOriginalSource);
					} else {
						Log.d(TAG, "Still pending broadcasts for UUID " + broadcastStatus.getRequestUuid() + ". Waiting for more responses.");
					}
				});
			} else {
				Log.w(TAG, "Failed to update BroadcastStatusEntry for " + broadcastStatus.getRequestUuid() + " to " + sender.getAddressName() + " to isPendingResponseInProgress=true.");
			}
		});
	}

	private void handleIncomingRouteResponseRouteNotFoundStatuses(
			RouteResponseMessage routeResponse, BroadcastStatusEntry broadcastStatus, Node sender,
			Node destination, @Nullable Node previousHop, boolean isOriginalSource) {
		routeRepository.deleteBroadcastStatus(broadcastStatus, ok -> {
			if (ok) {
				Log.d(TAG, "BroadcastStatusEntry for " + broadcastStatus.getRequestUuid() + " to " + sender.getAddressName() + " deleted (success).");
				DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.BROADCAST_STATUS_DEL, routeResponse.getRequestUuid(),
						params(null, null, routeResponse.getStatus().name(), routeResponse.getHopCount()));

				routeRepository.hasBroadcastStatusInProgressState(broadcastStatus.getRequestUuid(), false, isBroadcastRemaining -> {
					if (!Boolean.TRUE.equals(isBroadcastRemaining)) {
						// All branches have response.
						routeRepository.hasBroadcastStatusInProgressState(broadcastStatus.getRequestUuid(), true, isRequestInProgress -> {
							if (Boolean.TRUE.equals(isRequestInProgress)) {
								Log.d(TAG, "All branches processed for UUID " + broadcastStatus.getRequestUuid() + ". Found REQUEST_ALREADY_IN_PROGRESS branches. Executing completion with REQUEST_ALREADY_IN_PROGRESS.");
								RouteResponseMessage nextResponse = routeResponse.toBuilder()
										.setStatus(RouteResponseMessage.Status.REQUEST_ALREADY_IN_PROGRESS)
										.build();
								finalizeHandlingRouteNotFound(nextResponse, destination, previousHop, isOriginalSource);
							} else {
								Log.d(TAG, "All branches processed for UUID " + broadcastStatus.getRequestUuid() + ". No REQUEST_ALREADY_IN_PROGRESS branches. Executing completion with received status: " + routeResponse.getStatus());
								finalizeHandlingRouteNotFound(routeResponse, destination, previousHop, isOriginalSource);
							}
						});
					} else {
						Log.d(TAG, "Still pending broadcasts for UUID " + broadcastStatus.getRequestUuid() + ". Waiting for more responses.");
					}
				});
			} else {
				Log.w(TAG, "Failed to delete BroadcastStatusEntry for " + broadcastStatus.getRequestUuid() + " to " + sender.getAddressName() + " (failure).");
			}
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
			@NonNull String senderAddressName, @NonNull RouteResponseMessage routeResponse) {
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
		//       ii. Next_hop of this RouteEntry = N_sender of this response (the node that just sent us the ROUTE_FOUND)
		//          and Previous_hop = N_previous_hop_of_the_request (the node that sent us the original request).
		//       iii. Hop count = message_response_route.hop_count `+ ifSource ? 0 : 1` (the total hops from the original source).
		//       iv. Sets the RouteEntry `last_use_timestamp` to the current time.
		//       v. Save the RouteEntry in the database.
		//       vi. Delete all BroadcastStatusEntry entries for this UUID.
		//    c. If N_previous_hop_of_the_request is NULL (meaning N_current is the original source node):
		//       i. The route is established. Use a dedicated method `NearbyManager.onRouteFound` which will be
		//          responsible for necessary processing.
		//    d. Else (N_current is an intermediate node):
		//       i. Forward the message_response_route (with ROUTE_FOUND status) to N_previous_hop_of_the_request after increment the hop_count

		// 5. If status == REQUEST_ALREADY_IN_PROGRESS:
		//    a. Update the BroadcastStatusEntry for the UUID and N_sender: set isPendingResponseInProgress = true.
		//    b. Check if there are other BroadcastStatusEntry entries for this UUID with (isPendingResponseInProgress == false),
		//       i.e., not yet processed.
		//    c. If no BroadcastStatusEntry with (isPendingResponseInProgress == false) is found for this UUID
		//       (meaning all BroadcastStatusEntry entries have just been processed for this request_uuid):
		//       i. Delete all BroadcastStatusEntry entries for that request_uuid.
		//       ii. Delete the RouteRequestEntry.
		//       iii. If N_previous_hop_of_the_request is NULL (N_current is the original source node):
		//          1. Undesirable behavior: given that the source is the sole holder of the request,
		//             it's not normal for an immediate neighbor to respond this way.
		//          2. It looks like a UUID collision. Log the error.
		//          3. Notify the upper layer via `NearbyManager.onRouteNotFound`. Return.
		//       iv. Else (N_current is an intermediate node):
		//          1. Forward the original message_response_route (with REQUEST_ALREADY_IN_PROGRESS status)
		//             to N_previous_hop_of_the_request.
		//    d. Else (there are still other pending responses), wait for them to be solved. Return.

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
			public void onNodeReady(@NonNull Node sender) {
				// 2.
				// Find RouteRequestEntry by requestUuid
				routeRepository.findRouteRequestByUuidAsync(requestUuid, new SelectionCallback<>() {
					@Override
					public void onSuccess(@NonNull RouteRequestEntry routeRequestEntry) {
						Long previousHopLocalId = routeRequestEntry.getPreviousHopLocalId(); // Null if current node is original source

						executor.execute(() -> {
							// Get destination node address name for logging and callbacks
							Node destinationNode = nodeRepository.findNodeSync(routeRequestEntry.getDestinationNodeLocalId());
							if (destinationNode == null) {
								// Undesired behavior, the node must be set at route request entry mapping
								Log.e(TAG, "Undesired behavior, the node must be set at route request entry mapping");
								return;
							}

							// 3.
							BroadcastStatusEntry broadcastStatus = routeRepository.findBroadcastStatusSync(requestUuid, sender.getId());
							if (broadcastStatus == null) {
								Log.e(TAG, "RouteResponseMessage for UUID " + requestUuid + " from " + senderAddressName + " received, but no corresponding BroadcastStatusEntry found. This is unexpected. Ignoring response.");
								return;
							}

							// Helper to determine if current node is the original source
							boolean isOriginalSource = (previousHopLocalId == null);
							Node previousHopNode = previousHopLocalId != null ? nodeRepository.findNodeSync(previousHopLocalId) : null;

							DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_RESP_RCVD, requestUuid,
									params(destinationNode.getAddressName(), null, responseStatus.name(), routeResponse.getHopCount()));
							// 4. If status == ROUTE_FOUND:
							if (responseStatus == RouteResponseMessage.Status.ROUTE_FOUND) {
								handleIncomingRouteResponseRouteFound(routeResponse, sender, destinationNode, previousHopNode, isOriginalSource);
							} else if (responseStatus == RouteResponseMessage.Status.REQUEST_ALREADY_IN_PROGRESS) {
								handleIncomingRouteResponseAlreadyInProgress(routeResponse, broadcastStatus, sender, destinationNode, previousHopNode, isOriginalSource);
							} else {
								handleIncomingRouteResponseRouteNotFoundStatuses(routeResponse, broadcastStatus, sender, destinationNode, previousHopNode, isOriginalSource);
							}
						});
					}

					@Override
					public void onFailure(@Nullable Exception cause) {
						DataLog.logRouteEvent(DataLog.RouteDiscoveryEvent.ROUTE_RESP_LATE, requestUuid,
								params(null, senderAddressName, responseStatus.name(), routeResponse.getHopCount()));
						Log.w(TAG, "RouteResponseMessage with UUID " + requestUuid + " received, but no corresponding RouteRequestEntry found. Ignoring response.");
					}
				});
			}

			@Override
			public void onError(@NonNull String errorMessage) {
				Log.e(TAG, "Failed to resolve or create sender node " + senderAddressName + ": " + errorMessage + ". Aborting RouteResponseMessage processing.");
			}
		});
	}

	// Route destroying methods

	/**
	 * Adds non-null items to the provided collection.
	 *
	 * @param <T>   The generic type of the items and the collection.
	 * @param list  The collection to add items to.
	 * @param items The items to add. Only non-null items will be added.
	 */
	@SafeVarargs
	private <T> void addIfNotNull(Collection<T> list, T... items) {
		for (T item : items) {
			if (item != null) {
				list.add(item);
			}
		}
	}

	/**
	 * Sends a {@link RouteDestroyMessage} to a specific neighbor to invalidate a given route.
	 *
	 * @param neighborAddressName The address name of the neighbor to send the message to.
	 * @param routeUuid           The UUID of the route in destruction.
	 */
	private void dispatchRouteDestroyToNeighbor(@NonNull String neighborAddressName, @NonNull String routeUuid) {
		RouteDestroyMessage routeDestroyMessage = RouteDestroyMessage.newBuilder()
				.setRouteUuid(routeUuid).build();
		NearbyMessageBody nearbyMessageBody = NearbyMessageBody.newBuilder()
				.setMessageType(NearbyMessageBody.MessageType.ROUTE_DESTROY)
				.setBinaryData(routeDestroyMessage.toByteString()).build();
		nearbyManager.encryptAndSendInternal(null, neighborAddressName, nearbyMessageBody, TransmissionCallback.NULL_CALLBACK);
	}

	/**
	 * Finds all unique node addresses that are part of a given route or its usages.
	 * This includes the previous and next hops of the main route entry (if provided)
	 * and the previous hops recorded in any {@link RouteUsage} entries associated with the route UUID.
	 *
	 * @param routeUuid The UUID of the route for which to find affected addresses.
	 * @param route     The {@link RouteEntry} corresponding to the route UUID. Can be null if the route itself
	 *                  is already deleted but usages might still exist.
	 * @return A list of unique node address names that were part of the route or its usages about the host node,
	 * or {@code null} if no nodes could be resolved.
	 */
	@Nullable
	private List<String> findRouteAffectedAddresses(String routeUuid, RouteEntry route) {
		List<RouteUsage> usages = routeRepository.findRouteUsagesByRouteUuidSync(routeUuid);
		Set<Long> uniques = new HashSet<>();
		if (route != null) {
			addIfNotNull(uniques, route.getPreviousHopLocalId(), route.getNextHopLocalId());
		}
		if (usages != null) {
			for (RouteUsage usage : usages) {
				addIfNotNull(uniques, usage.getPreviousHopLocalId());
			}
		}
		List<Long> ids = new ArrayList<>(uniques);
		return nodeRepository.findAddressesForSync(ids);
	}

	/**
	 * Dispatches a {@link RouteDestroyMessage} to all relevant neighbors (participants of the route)
	 * to inform them about the destruction of a specific route.
	 * It also cleans up the route and its usages from the local repository.
	 *
	 * @param routeUuid     The UUID of the route to be destroyed.
	 * @param exceptAddress An optional node address name that should be excluded from receiving the
	 *                      {@link RouteDestroyMessage}. This is typically the node from which a
	 *                      route destruction message was received.
	 */
	private void dispatchRouteDestroy(@NonNull String routeUuid, @Nullable String exceptAddress) {
		executor.execute(() -> {
			Log.d(TAG, "Dispatching RouteDestroyMessage to all neighbors for route " + routeUuid);
			RouteEntry route = routeRepository.findRouteByDiscoveryUuidSync(routeUuid);
			List<String> addresses = findRouteAffectedAddresses(routeUuid, route);
			if (addresses != null) {
				if (exceptAddress != null) {
					addresses.remove(exceptAddress);
				}
				for (String address : addresses) {
					dispatchRouteDestroyToNeighbor(address, routeUuid);
				}
			}
			if (route != null) {
				routeRepository.dropRouteAndItsUsages(route);
			} else {
				// If the route entry is gone, try to drop usages by UUID
				routeRepository.dropRouteUsages(routeUuid);
			}
		});
	}

	/**
	 * Handles an incoming {@link RouteDestroyMessage} received from a neighbor.
	 * This method will propagate the route destruction to other relevant neighbors
	 * and clean up the local route information.
	 *
	 * @param routeDestroyMessage The received {@link RouteDestroyMessage}.
	 * @param senderAddressName   The address name of the node that sent the destroy message.
	 *                            This address will be excluded from further propagation of this message.
	 */
	protected void handleIncomingRouteDestroyMessage(
			@NonNull RouteDestroyMessage routeDestroyMessage, @NonNull String senderAddressName) {
		dispatchRouteDestroy(routeDestroyMessage.getRouteUuid(), senderAddressName);
	}

	// Routed messages exchange methods

	/**
	 * Helper method to send a RoutedMessage to the next hop.
	 * This method runs on the caller's thread.
	 */
	private void sendRoutedMessageToNextHop(
			@NonNull String nextHopAddressName, @NonNull RoutedMessage routedMessage,
			@NonNull RouteEntry route, @Nullable TransmissionCallback callback) {
		try {
			Log.d(TAG, "Sending RoutedMessage to next hop: " + nextHopAddressName);
			// Create an OUTER NearbyMessageBody with the RoutedMessage
			// The `encrypted_routed_message_body` is left untouched, as it's E2E encrypted.
			NearbyMessageBody outerNearbyMessageBody = NearbyMessageBody.newBuilder()
					.setMessageType(NearbyMessageBody.MessageType.ROUTED_MESSAGE)
					.setBinaryData(routedMessage.toByteString())
					.build();
			TransmissionCallback transmissionCallback = new TransmissionCallback() {
				@Override
				public void onSuccess(@NonNull Payload payload) {
					if (callback != null) {
						callback.onSuccess(payload);
					}
					Log.d(TAG, "RoutedMessage sent to next hop: " + nextHopAddressName);
					route.setLastUseTimestamp(System.currentTimeMillis());
					routeRepository.updateRouteEntry(route, null);
				}

				@Override
				public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
					if (callback != null) {
						callback.onFailure(payload, cause);
					}
					Log.e(TAG, "Failed to send RoutedMessage to next hop: " + nextHopAddressName, cause);
				}
			};

			// Use NearbyManager to send the raw payload bytes
			nearbyManager.encryptAndSendInternal(null, nextHopAddressName, outerNearbyMessageBody, transmissionCallback);
		} catch (Exception e) {
			Log.e(TAG, "Error building or sending RoutedMessage to " + nextHopAddressName, e);
		}
	}

	@Nullable
	protected RouteWithUsage getIfExistRouteAndUsageFor(@NonNull String remoteAddressName) {
		// 1. Resolve finalDestinationAddressName to local ID
		Node finalDestinationNode = nodeRepository.findNodeSync(remoteAddressName);
		if (finalDestinationNode == null) {
			Log.e(TAG, "Final destination node " + remoteAddressName + " not found in local repository.");
			return null;
		}

		// 2. Find an active route to the final destination
		RouteWithUsage entryUsage = routeRepository.findMostRecentRouteByDestinationSync(finalDestinationNode.getId());
		if (entryUsage == null) {
			Log.e(TAG, "No active route found for destination " + remoteAddressName + ". Please initiate route discovery.");
			return null;
		}
		if (entryUsage.routeEntry.getLastUseTimestamp() == null) {
			Log.w(TAG, "Route found but unusable.");
			return null;
		}
		long delay = System.currentTimeMillis() - Objects.requireNonNullElse(entryUsage.routeEntry.getLastUseTimestamp(), 0L);
		if (delay > ROUTE_MAX_INACTIVITY_MILLIS) {
			Log.w(TAG, "Match expired route. Delete it.");
			dispatchRouteDestroy(entryUsage.routeEntry.getDiscoveryUuid(), null);
			return null;
		}
		return entryUsage;
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
	 *
	 * @param finalDestinationAddressName The {@code SignalProtocolAddress.name} of the ultimate
	 *                                    recipient node for this message.
	 * @param originalSenderAddressName   The {@code SignalProtocolAddress.name} of the node
	 *                                    that originally sent this message.
	 * @param routeWithUsage              Wrapper of the route to use and its usages
	 * @param internalNearbyMessageBody   The {@link NearbyMessageBody} representing the actual
	 *                                    application-level message (e.g., chat message, personal info, ACK). This object
	 *                                    is *unencrypted* at this stage and will be encrypted end-to-end within this method.
	 * @param callback                    A callback that will be invoked with the Nearby Connections
	 *                                    {@link Payload} and match success or failure of the transmission.
	 */
	public void sendMessageThroughRoute(
			@NonNull String finalDestinationAddressName, @NonNull String originalSenderAddressName,
			@NonNull RouteWithUsage routeWithUsage,
			@NonNull NearbyMessageBody internalNearbyMessageBody, // The actual message, UNENCRYPTED
			@NonNull TransmissionCallback callback
	) {
		executor.execute(() -> {
			Log.d(TAG, "Attempting to send message to " + finalDestinationAddressName + " from " + originalSenderAddressName);

			// 1. Resolve finalDestinationAddressName to local ID
			Node finalDestinationNode = nodeRepository.findNodeSync(finalDestinationAddressName);
			if (finalDestinationNode == null) {
				Log.e(TAG, "Final destination node " + finalDestinationAddressName + " not found in local repository.");
				callback.onFailure(null, null);
				return;
			}

			// 2. Get the active route to the final destination
			RouteEntry activeRoute = routeWithUsage.routeEntry;
			RouteUsage usage = routeWithUsage.routeUsage;
			// Identify the next hop node for this route
			Long nextHopLocalId;
			boolean isForBacktracking;
			String usageUuid;
			if (routeWithUsage.isWithoutUsage() || Objects.equals(finalDestinationNode.getId(), activeRoute.getDestinationNodeLocalId())) {
				/*
				 * [`routeWithUsage.isWithoutUsage()`]
				 * This node is just an intermediate node on this route.
				 * So the route's destination match the final destination.
				 * [`Objects.equals(finalDestinationNode.getId(), activeRoute.getDestinationNodeLocalId())`]
				 * The route point to the final destination.
				 */
				nextHopLocalId = activeRoute.getNextHopLocalId();
				isForBacktracking = false;
				usageUuid = activeRoute.getDiscoveryUuid();
			} else {
				// We are in case of backtracking
				if (usage == null) {
					Log.e(TAG, "Impossible to find the RouteUsage for destination " + finalDestinationAddressName + ". The next hop is to extracted from the RouteUsage.");
					callback.onFailure(null, null);
					return;
				}
				nextHopLocalId = usage.getPreviousHopLocalId();
				isForBacktracking = true;
				usageUuid = usage.getUsageRequestUuid();
			}

			// 3. Resolve the next hop node for this route
			Node nextHopNode;
			if (nextHopLocalId == null || (nextHopNode = nodeRepository.findNodeSync(nextHopLocalId)) == null) {
				Log.e(TAG, "Next hop node for route " + activeRoute.getDiscoveryUuid() + " not found. Route might be stale/invalid.");
				dispatchRouteDestroy(activeRoute.getDiscoveryUuid(), null); // Invalidate the bad route
				callback.onFailure(null, null);
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
				encryptedRoutedMessageBody = NearbySignalMessenger.getInstance().encrypt(routedMessageBody.toByteArray(), finalDestinationAddressName);
				Log.d(TAG, "RoutedMessageBody encrypted for " + finalDestinationAddressName);
			} catch (Exception e) {
				Log.e(TAG, "Failed to E2E encrypt RoutedMessageBody for " + finalDestinationAddressName + ": " + e.getMessage(), e);
				callback.onFailure(null, e);
				return;
			}

			// 5. Create the hop-by-hop RoutedMessage
			// This contains routing info + the E2E encrypted payload
			RoutedMessage routedMessage = RoutedMessage.newBuilder()
					.setFinalDestinationNodeId(finalDestinationAddressName)
					.setRouteUuid(activeRoute.getDiscoveryUuid())
					.setRouteUsageUuid(usageUuid)
					.setEncryptedRoutedMessageBody(ByteString.copyFrom(encryptedRoutedMessageBody.serialize()))
					.setOriginalSenderNodeId(originalSenderAddressName)
					.setForBacktracking(isForBacktracking)
					.build();

			// 6. Send the RoutedMessage to the next hop
			// This part will be encrypted hop-by-hop by NearbySignalMessenger
			sendRoutedMessageToNextHop(nextHopAddressName, routedMessage, activeRoute, callback);
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
	 * encrypted payload remains untouched by intermediate nodes. If the payload ID
	 * is defined if it isn't defined yet.
	 * <p>
	 * This method also updates the {@link RouteEntry} last use timestamp
	 * for the route being utilized.
	 *
	 * @param incomingRoutedMessage The deserialized {@link RoutedMessage} received, containing
	 *                              routing information and the end-to-end encrypted payload.
	 * @param localHostAddressName  The {@code SignalProtocolAddress.name} of the current local node.
	 * @param payloadId             The ID of the original Nearby Connections payload that carried this message.
	 *                              Used primarily for logging and tracking.
	 */
	public void handleIncomingRoutedMessage(
			@NonNull RoutedMessage incomingRoutedMessage, // The deserialized RoutedMessage
			@NonNull String localHostAddressName, long payloadId
	) {
		executor.execute(() -> {
			String finalDestinationAddressName = incomingRoutedMessage.getFinalDestinationNodeId();
			String routeUuid = incomingRoutedMessage.getRouteUuid();

			Log.d(TAG, "Handling incoming RoutedMessage for final destination: " +
					finalDestinationAddressName + " via route UUID: " + routeUuid);

			// 1. Check if current node is the final destination
			if (finalDestinationAddressName.equals(localHostAddressName)) {
				Log.d(TAG, "Current node is the final destination for RoutedMessage with UUID: " + routeUuid);

				// a. Decrypt the end-to-end encrypted payload (RoutedMessageBody)
				RoutedMessageBody decryptedRoutedMessageBody;
				try {
					byte[] cipherData = incomingRoutedMessage.getEncryptedRoutedMessageBody().toByteArray();
					byte[] decryptedBytes = NearbySignalMessenger.getInstance().decrypt(cipherData, incomingRoutedMessage.getOriginalSenderNodeId());
					decryptedRoutedMessageBody = RoutedMessageBody.parseFrom(decryptedBytes);
				} catch (Exception e) {
					Log.e(TAG, "Failed to E2E decrypt RoutedMessageBody for UUID " + routeUuid, e);
					return;
				}

				// b. Extract original sender and the internal NearbyMessageBody
				String originalSenderNodeId = incomingRoutedMessage.getOriginalSenderNodeId();
				NearbyMessageBody internalNearbyMessageBody = decryptedRoutedMessageBody.getInternalMessageBody();
				Log.d(TAG, "Message for " + finalDestinationAddressName + " from original sender " + originalSenderNodeId + " received.");
				// c. Deliver the internalNearbyMessageBody to the application layer via NearbyManager
				nearbyManager.onRoutedMessageReceived(originalSenderNodeId, internalNearbyMessageBody,
						incomingRoutedMessage.getPayloadId() != 0L ? incomingRoutedMessage.getPayloadId() : payloadId);
			} else {
				// 2. Current node is an intermediate node, forward the message
				Log.d(TAG, "Current node is an intermediate node for RoutedMessage to " + finalDestinationAddressName);

				RouteEntry activeRoute = routeRepository.findRouteByDiscoveryUuidSync(routeUuid);
				RouteUsage usage = routeRepository.findRouteUsageByUsageUuidSync(incomingRoutedMessage.getRouteUsageUuid());

				if (activeRoute == null) {
					Log.e(TAG, "Unable to locate the route for request ID: " + routeUuid);
					// The route is probably invalidated
					dispatchRouteDestroy(routeUuid, null);
					return;
				}
				Long nextHopLocalId;
				if (incomingRoutedMessage.getForBacktracking()) {
					// We are in case of backtracking
					if (usage != null && usage.getPreviousHopLocalId() != null) {
						// Usage previous hop is the correct to fetch cases of route reuse
						nextHopLocalId = usage.getPreviousHopLocalId();
					} else {
						// The node is just an intermediate node on this route.
						nextHopLocalId = activeRoute.getPreviousHopLocalId();
					}
				} else {
					nextHopLocalId = activeRoute.getNextHopLocalId();
				}

				// b. Resolve the next hop
				Node nextHopNode;
				if (nextHopLocalId == null || (nextHopNode = nodeRepository.findNodeSync(nextHopLocalId)) == null) {
					Log.e(TAG, "Next hop node for route " + activeRoute.getDiscoveryUuid() + " not found on intermediate node. Route might be stale/invalid.");
					dispatchRouteDestroy(routeUuid, null); // Invalidate the bad route
					return;
				}
				String nextHopAddressName = nextHopNode.getAddressName();
				RoutedMessage routedMessage;
				if (incomingRoutedMessage.getPayloadId() != 0L) {
					routedMessage = incomingRoutedMessage; // Re-use the existing RoutedMessage bytes
				} else {
					routedMessage = incomingRoutedMessage.toBuilder().setPayloadId(payloadId).build();
				}

				sendRoutedMessageToNextHop(nextHopAddressName, routedMessage, activeRoute, null);
			}
		});
	}

	interface BroadcastRequestCallback {
		void onSuccess(@NotNull String neighborAddressName);

		void onFailure(@NotNull String neighborAddressName);
	}
}
