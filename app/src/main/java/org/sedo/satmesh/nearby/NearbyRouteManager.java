package org.sedo.satmesh.nearby;

import static org.sedo.satmesh.signal.SignalManager.getAddress;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.protobuf.ByteString;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.rt.BroadcastStatusEntry;
import org.sedo.satmesh.model.rt.BroadcastStatusEntryDao;
import org.sedo.satmesh.model.rt.RouteEntryDao;
import org.sedo.satmesh.model.rt.RouteRequestEntryDao;
import org.sedo.satmesh.model.rt.RouteUsageDao;
import org.sedo.satmesh.proto.NearbyMessage;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.RouteRequestMessage;
import org.sedo.satmesh.proto.RouteResponseMessage;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.CiphertextMessage;

import java.util.concurrent.ExecutorService;

public class NearbyRouteManager {

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
	 * Handles an incoming RouteRequestMessage.
	 * This method is called by NearbySignalMessenger after decrypting a ROUTE_DISCOVERY_REQ.
	 *
	 * @param endpointId        The Nearby endpoint ID from which the message was received.
	 * @param senderAddressName The SignalProtocolAddress.name of the sender node.
	 * @param routeRequest      The parsed RouteRequestMessage Protobuf object.
	 * @param payloadId         The Nearby Payload ID.
	 */
	public void handleIncomingRouteRequest(
			@NonNull String endpointId,
			@NonNull String senderAddressName,
			@NonNull RouteRequestMessage routeRequest,
			long payloadId) {
		executor.execute(() -> {
			Log.d(TAG, "Handling incoming RouteRequestMessage from " + senderAddressName +
					" for UUID: " + routeRequest.getUuid() +
					", Destination: " + routeRequest.getDestinationNodeId() +
					", Remaining Hops: " + routeRequest.getRemainingHops() +
					", Payload ID: " + payloadId);

			// TODO: Implement the detailed logic for RouteRequestMessage handling:
			// 1. Resolve senderAddressName to senderNodeId (local ID).
			// 2. Check if UUID exists in RouteRequestEntry (prevent loops/redundant processing).
			//    - If exists, send "REQUEST_ALREADY_IN_PROGRESS" response.
			// 3. Check TTL and remaining_hops.
			//    - If expired/zero, send "TTL_EXPIRED" or "MAX_HOPS_REACHED" response.
			// 4. If valid and new:
			//    - Check if current node is the destination.
			//      - If yes, send "ROUTE_FOUND" response (backtracking).
			//      - If no,
			//        - Handle case of no other neighbors (send NO_ROUTE_FOUND).
			//        - Store RouteRequestEntry
			//        - decrement hops, and forward the request to other neighbors
			//        (excluding sender and already-broadcasted ones).
			//        - Update BroadcastStatusEntry for the relayed requests.

			Log.d(TAG, "RouteRequestMessage processing stub completed for " + routeRequest.getUuid());
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

	/**
	 * Helper method to send a RouteRequestMessage.
	 * This method would be called internally by NearbyRouteManager.
	 *
	 * @param recipientAddressName The SignalProtocolAddress.name of the recipient.
	 * @param messageBody          The RouteRequestMessage to send.
	 * @param isInitialBroadcast   True if this is the first broadcast for this request (for BroadcastStatusEntry tracking).
	 */
	private void sendRouteRequestMessage(
			@NonNull String recipientAddressName, @NonNull RouteRequestMessage messageBody, boolean isInitialBroadcast) {
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
				nearbyManager.sendNearbyMessageInternal(nearbyMessage, recipientAddressName, (payload, success)  -> {
						executor.execute(() -> {
							if (success) {
								Log.d(TAG, "RouteRequestMessage payload " + payload.getId() + " sent successfully to " + recipientAddressName);
								// If this is an initial broadcast, we need to track its status
//								if (isInitialBroadcast) {
//									nodeRepository.findNodeByAddressName(recipientAddressName, node -> {
//										if (node != null) {
//											BroadcastStatusEntry broadcastStatus = new BroadcastStatusEntry();
//											broadcastStatus.setRequestUuid(messageBody.getUuid());
//											broadcastStatus.setNeighborNodeLocalId(node.getId());
//											broadcastStatus.setPendingResponseInProgress(false); // Initially not pending "in progress" response
//											broadcastStatusEntryDao.insert(broadcastStatus);
//											Log.d(TAG, "BroadcastStatusEntry created for request " + messageBody.getUuid() + " to neighbor " + node.getId());
//										} else {
//											Log.e(TAG, "Failed to find node for " + recipientAddressName + " after sending RouteRequestMessage.");
//										}
//									});
//								}
							} else {
								Log.e(TAG, "Failed to send RouteRequestMessage payload " + payload.getId() + " to " + recipientAddressName);
								// TODO: Handle failed send: maybe mark BroadcastStatusEntry as failed or remove it.
							}
						});
				});
			} catch (Exception e) {
				Log.e(TAG, "Error building or sending RouteRequestMessage to " + recipientAddressName + ": " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Helper method to send a RouteResponseMessage.
	 * This method would be called internally by NearbyRouteManager.
	 *
	 * @param recipientAddressName The SignalProtocolAddress.name of the recipient.
	 * @param messageBody          The RouteResponseMessage to send.
	 */
	private void sendRouteResponseMessage(
			@NonNull String recipientAddressName,
			@NonNull RouteResponseMessage messageBody) {
		executor.execute(() -> {
			try {
				Log.d(TAG, "Sending RouteResponseMessage to " + recipientAddressName + " for UUID: " + messageBody.getRequestUuid() + " with status: " + messageBody.getStatus());
				NearbyMessageBody nearbyMessageBody = NearbyMessageBody.newBuilder()
						.setMessageType(NearbyMessageBody.MessageType.ROUTE_DISCOVERY_RESP)
						.setEncryptedData(messageBody.toByteString())
						.build();

				// NearbyManager handles encryption and payload sending
//				nearbyManager.sendEncryptedMessage(recipientAddressName, nearbyMessageBody, new NearbyManager.PayloadSentCallback() {
//					@Override
//					public void onPayloadSent(long payloadId, boolean success) {
//						if (success) {
//							Log.d(TAG, "RouteResponseMessage payload " + payloadId + " sent successfully to " + recipientAddressName);
//						} else {
//							Log.e(TAG, "Failed to send RouteResponseMessage payload " + payloadId + " to " + recipientAddressName);
//							// TODO: Handle failed send, e.g., if the link broke.
//						}
//					}
//				});
			} catch (Exception e) {
				Log.e(TAG, "Error building or sending RouteResponseMessage to " + recipientAddressName + ": " + e.getMessage(), e);
			}
		});
	}
}
