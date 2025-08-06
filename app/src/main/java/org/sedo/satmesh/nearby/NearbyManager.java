package org.sedo.satmesh.nearby;

import android.content.Context;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.protobuf.ByteString;

import org.sedo.satmesh.model.rt.RouteEntry;
import org.sedo.satmesh.nearby.data.DeviceConnectionListener;
import org.sedo.satmesh.nearby.data.PayloadListener;
import org.sedo.satmesh.nearby.data.TransmissionCallback;
import org.sedo.satmesh.proto.NearbyMessage;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.RouteResponseMessage;
import org.sedo.satmesh.ui.data.NodeState;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;
import org.sedo.satmesh.utils.DataLog;
import org.sedo.satmesh.utils.DataLog.TransmissionEventType;
import org.sedo.satmesh.utils.DataLog.TransmissionStatus;
import org.whispersystems.libsignal.protocol.CiphertextMessage;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NearbyManager {

	private static final String TAG = "NearbyManager";
	private static final String SERVICE_ID = "org.sedo.satmesh.SECURE_MESSENGER"; // Unique service ID for the app
	private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
	// Connection status
	private final static int STATUS_FOUND = 0;
	private final static int STATUS_INITIATED_FROM_REMOTE = 1;
	private final static int STATUS_INITIATED_FROM_HOST = 2;
	private final static int STATUS_CONNECTED = 4;
	private final static int STATUS_DISCONNECTED = 5;
	private static volatile NearbyManager INSTANCE;

	/*
	 * Map to store endpoints connection state: endpointId -> remote device's state
	 */
	private final Map<String, ConnectionState> endpointStates = new ConcurrentHashMap<>();
	/*
	 * Maps device' SignalProtocol address to its endpoint
	 * This map will contain only *currently connected endpoints, as determined by the
	 * `connectedEndpointAddresses` map. It will be cleared on disconnect.
	 */
	private final Map<String, String> addressNameToEndpointId = new ConcurrentHashMap<>();
	private final List<DeviceConnectionListener> deviceConnectionListeners = new CopyOnWriteArrayList<>();// Thread-safe list
	private final List<PayloadListener> payloadListeners = new CopyOnWriteArrayList<>();
	private final ConnectionsClient connectionsClient;
	// Use local address name as advertising name
	private final String localAddressName;
	private final ExecutorService executorService;
	/**
	 * PayloadCallback handles incoming data payloads.
	 * It processes received bytes as Protobuf messages and dispatches them.
	 */
	private final PayloadCallback payloadCallback = new PayloadCallback() {
		@Override
		public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
			// Ensure payload has bytes and get the associated device name
			ConnectionState state = endpointStates.get(endpointId);
			if (state == null) {
				// This could happen if a payload arrives just after a disconnect event
				Log.w(TAG, "Payload received from unknown or disconnected endpoint: " + endpointId);
				return;
			}
			DataLog.logTransmissionEvent(TransmissionEventType.RECEIVE, endpointId, payload.getId(), 0, TransmissionStatus.SUCCESS);
			if (state.status != STATUS_CONNECTED) {
				// Inconsistent state, we are receiving payload from the device, so we ae connected
				Log.d(TAG, "Inconsistent state, we are receiving payload from the device in status: "
						+ state.status + ", distinct of connection status");
				putState(endpointId, state.addressName, STATUS_CONNECTED, NodeState.ON_CONNECTED);
			}
			Log.d(TAG, "Payload received from " + state.addressName + " (Endpoint ID: " + endpointId + ")");
			payloadListeners.forEach(l -> l.onPayloadReceived(endpointId, payload));
		}

		@Override
		public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
			/*
			 * Handle payload transfer updates (e.g., progress, completion).
			 * For small messages like these, it might not be strictly necessary,
			 * but can be useful for larger file transfers.
			 */
			Log.d(TAG, "Payload transfer update: " + update.getPayloadId() + " Status: " + update.getStatus() +
					" Bytes: " + update.getBytesTransferred() + "/" + update.getTotalBytes() +
					" from " + endpointId);
			payloadListeners.forEach(l -> l.onPayloadTransferUpdate(endpointId, update));
		}
	};
	/**
	 * ConnectionLifecycleCallback handles the lifecycle of connections.
	 * It initiates connection, processes results, and handles disconnections.
	 */
	private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
		@Override
		public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
			ConnectionState state = endpointStates.get(endpointId);
			if (state != null) {
				Log.d(TAG, "Receiving connection in state=" + state);
			}
			DataLog.logNodeEvent(DataLog.NodeDiscoveryEvent.INIT_BY_REMOTE, connectionInfo.getEndpointName(), endpointId, null, null);
			Log.d(TAG, "Connection initiated with: " + endpointId + " Name: " + connectionInfo.getEndpointName());
			putState(endpointId, connectionInfo.getEndpointName(), STATUS_INITIATED_FROM_REMOTE, NodeState.ON_CONNECTING);
			addressNameToEndpointId.put(connectionInfo.getEndpointName(), endpointId); // Keep track of pending connections
			deviceConnectionListeners.forEach(listener -> listener.onConnectionInitiated(endpointId, connectionInfo.getEndpointName()));
			// NearbySignalMessenger, as listener, will now explicitly call acceptConnection, allowing for async processing.
		}

		@Override
		public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
			ConnectionState state = endpointStates.get(endpointId);
			if (state == null) {
				// Very very bad
				Log.e(TAG, "onConnectionResult: Endpoint ID " + endpointId + " not found in connection state map. Orphaned connection result.");
				return;
			}
			String remoteAddressName = state.addressName;
			if (result.getStatus().isSuccess()) {
				Log.d(TAG, "Connection established with: " + remoteAddressName + " (EndpointId: " + endpointId + ")");
				int status = state.status;
				// Store the mapping between endpointId and the remote device's SignalProtocolAddress name
				putState(endpointId, remoteAddressName, STATUS_CONNECTED, NodeState.ON_CONNECTED);
				addressNameToEndpointId.put(remoteAddressName, endpointId); // Update the core mapping

				deviceConnectionListeners.forEach(listener -> listener.onDeviceConnected(endpointId, remoteAddressName));
				if (status == STATUS_INITIATED_FROM_REMOTE) {
					NearbySignalMessenger.getInstance().handleInitialKeyExchange(remoteAddressName);
				}
			} else {
				Log.e(TAG, "Connection failed with: " + remoteAddressName + " (EndpointId: " + endpointId + "). Status: " + result.getStatus().getStatusMessage());
				deviceConnectionListeners.forEach(l -> l.onConnectionFailed(endpointId, remoteAddressName, result.getStatus()));
				// If connection failed reset in found state, then the host device can request connection again
				putState(endpointId, remoteAddressName, STATUS_FOUND, NodeState.ON_ENDPOINT_FOUND);
			}
		}

		@Override
		public void onDisconnected(@NonNull String endpointId) {
			Log.d(TAG, "Disconnected from: " + endpointId);
			ConnectionState state = endpointStates.remove(endpointId);
			if (state == null) {
				Log.w(TAG, "onDisconnected: Endpoint ID " + endpointId + " not found in the endpoints map.");
				return;
			}
			String deviceAddress = state.addressName;
			addressNameToEndpointId.remove(deviceAddress); // Remove from the core mapping
			if (state.status == STATUS_CONNECTED) {
				deviceConnectionListeners.forEach(l -> l.onDeviceDisconnected(endpointId, deviceAddress));
			} else {
				// Notify as a connection failed, as it never truly completed.
				deviceConnectionListeners.forEach(l -> l.onConnectionFailed(endpointId, deviceAddress, Status.RESULT_CANCELED));
			}
			putState(endpointId, state.addressName, STATUS_DISCONNECTED, NodeState.ON_DISCONNECTED);
		}
	};

	EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
		@Override
		public void onEndpointFound(@NonNull String endpointId, DiscoveredEndpointInfo info) {
			Log.d(TAG, "Endpoint found: " + info.getEndpointName() + " with ID: " + endpointId);
			if (localAddressName.equals(info.getEndpointName())) {
				Log.w(TAG, "Node self auto-detect, address=" + localAddressName);
				return;
			}

			// Add to addressNameToEndpointId if not already present
			addressNameToEndpointId.put(info.getEndpointName(), endpointId);
			ConnectionState state = endpointStates.get(endpointId);

			// Check if this finding is really new
			if (state != null) {
				Log.i(TAG, "Endpoint " + info.getEndpointName() + " already connected or in pending state. Not requesting connection.");
				return;
			}

			putState(endpointId, info.getEndpointName(), STATUS_FOUND, NodeState.ON_ENDPOINT_FOUND);
			deviceConnectionListeners.forEach(l -> l.onEndpointFound(endpointId, info.getEndpointName()));
			/*
			 * We don't automatically request connection here. `NearbySignalMessenger`
			 * will do that based on its session logic or explicit user action after discovering.
			 */
		}

		@Override
		public void onEndpointLost(@NonNull String endpointId) {
			ConnectionState state = endpointStates.get(endpointId);
			if (state == null) {
				Log.w(TAG, "onEndpointLost: unable to locate the endpoint with ID: " + endpointId);
				return;
			}
			String endpointName = state.addressName;
			Log.d(TAG, "Endpoint lost: " + state + " (endpoint.idID: " + endpointId + ")");

			// Remove from relevant maps if it's not a currently connected endpoint
			// If it's connected, the onDisconnected callback will handle it.
			if (state.status == STATUS_CONNECTED) {
				DataLog.logNodeEvent(DataLog.NodeDiscoveryEvent.LOST, state.addressName, endpointId, null, "but still connected");
				return;
			}
			endpointStates.remove(endpointId);
			Log.d(TAG, "lost.state=" + state);
			NodeTransientStateRepository.getInstance().updateTransientNodeState(endpointName, NodeState.ON_ENDPOINT_LOST);
			deviceConnectionListeners.forEach(l -> l.onEndpointLost(endpointId, endpointName));
		}
	};
	/**
	 * True if we are advertising.
	 */
	private volatile boolean isAdvertising = false;
	/**
	 * True if we are discovering.
	 */
	private volatile boolean isDiscovering = false;

	private NearbyManager(@NonNull Context context, @NonNull String localAddressName) {
		this.connectionsClient = Nearby.getConnectionsClient(context);
		this.localAddressName = localAddressName;
		this.executorService = Executors.newSingleThreadExecutor();
	}

	public static NearbyManager getInstance(@NonNull Context context, @NonNull String localName) {
		if (INSTANCE == null) {
			synchronized (NearbyManager.class) {
				if (INSTANCE == null) {
					INSTANCE = new NearbyManager(context, localName);
				}
			}
		}
		return INSTANCE;
	}

	public static NearbyManager getInstance() {
		if (INSTANCE == null) {
			throw new RuntimeException("The nearby manager isn't initialized!");
		}
		return INSTANCE;
	}

	private void putState(@NonNull String endpointId, @NonNull String addressName, @ConnectivityStatus int status, @NonNull NodeState state) {
		endpointStates.put(endpointId, new ConnectionState(addressName, status));
		NodeTransientStateRepository.getInstance().updateTransientNodeState(addressName, state);
	}

	/**
	 * Adds the listener for device connection/disconnection events.
	 *
	 * @param listener The implementation of DeviceConnectionListener.
	 */
	public void addDeviceConnectionListener(DeviceConnectionListener listener) {
		if (listener != null && !this.deviceConnectionListeners.contains(listener)) {
			this.deviceConnectionListeners.add(listener);
		}
	}

	/**
	 * Removes the listener for device connection/disconnection events.
	 *
	 * @param listener The implementation of DeviceConnectionListener.
	 */
	public void removeDeviceConnectionListener(DeviceConnectionListener listener) {
		if (listener != null) {
			this.deviceConnectionListeners.remove(listener);
		}
	}

	/**
	 * Adds the listener for payload receiving events.
	 *
	 * @param listener The implementation of {@link PayloadListener}.
	 */
	public void addPayloadListener(PayloadListener listener) {
		if (listener != null && !this.payloadListeners.contains(listener)) {
			this.payloadListeners.add(listener);
		}
	}

	/**
	 * Removes the listener for discovering events.
	 *
	 * @param listener The implementation of {@link PayloadListener}.
	 */
	public void removePayloadListener(PayloadListener listener) {
		if (listener != null) {
			this.payloadListeners.remove(listener);
		}
	}

	/**
	 * Returns the SignalProtocolAddress name associated with a given Nearby endpoint ID.
	 * It checks both currently connected and pending/incoming connections.
	 *
	 * @param endpointId The ID of the Nearby endpoint.
	 * @return The SignalProtocolAddress name of the remote device, or null if not found.
	 */
	@Nullable
	public String getEndpointName(String endpointId) {
		ConnectionState state = endpointStates.get(endpointId);
		return state != null ? state.addressName : null;
	}

	@NonNull
	protected List<String> getConnectedEndpointsAddressNames() {
		return endpointStates.values().stream().
				filter(connectionState -> connectionState.status == NearbyManager.STATUS_CONNECTED)
				.map(connectionState -> connectionState.addressName)
				.collect(Collectors.toList());
	}

	/**
	 * Gets the linked endpoint ID to the specified node address name.
	 * This relies on the `addressNameToEndpointId` map which is populated
	 * upon successful connection or connection initiation.
	 *
	 * @param addressName The SignalProtocolAddress name to look up.
	 * @return The associated endpoint ID, or null if not found.
	 */
	@Nullable
	public String getLinkedEndpointId(@Nullable String addressName) {
		if (addressName == null) {
			return null;
		}
		return addressNameToEndpointId.get(addressName);
	}

	/**
	 * Returns {@code true} if currently advertising.
	 */
	public boolean isAdvertising() {
		return isAdvertising;
	}

	/**
	 * Starts advertising this device to be discovered by others.
	 * The advertising name used is the device's SignalProtocolAddress name.
	 */
	public void startAdvertising() {
		if (isAdvertising()) {
			Log.i(TAG, "Already advertising, skipping startAdvertising call.");
			return;
		}
		AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder()
				.setStrategy(STRATEGY).build();
		connectionsClient.startAdvertising(
						this.localAddressName,
						SERVICE_ID,
						connectionLifecycleCallback,
						advertisingOptions
				).addOnSuccessListener(unused -> {
					Log.d(TAG, "Advertising started successfully");
					isAdvertising = true;
				})
				.addOnFailureListener(e -> {
					if (e.getMessage() != null && e.getMessage().contains("STATUS_ALREADY_ADVERTISING")) {
						Log.d(TAG, "Advertising already started successfully");
						isAdvertising = true;
						return;
					}
					if (!isAdvertising) {
						Log.e(TAG, "Failed to start advertising", e);
					}
				});
	}

	/**
	 * Stops advertising.
	 */
	public void stopAdvertising() {
		if (!isAdvertising) {
			Log.i(TAG, "Not advertising, skipping stopAdvertising call.");
			return;
		}
		connectionsClient.stopAdvertising();
		isAdvertising = false;
		Log.d(TAG, "Advertising stopped");
	}

	public boolean isDiscovering() {
		return isDiscovering;
	}

	/**
	 * Requests connection to the remote device with the given endpoint ID.
	 *
	 * @param remoteEndpointId  The ID of the remote endpoint to connect to.
	 * @param remoteAddressName The SignalProtocolAddress name of the remote device. This helps track the connection.
	 */
	public void requestConnection(@NonNull String remoteEndpointId, @NonNull String remoteAddressName) {
		ConnectionState state = endpointStates.get(remoteEndpointId);
		if (state != null) {
			if (state.status == STATUS_CONNECTED) {
				Log.i(TAG, "Already connected to " + remoteAddressName + ". Skipping connection request.");
				NodeTransientStateRepository.getInstance().updateTransientNodeState(remoteAddressName, NodeState.ON_CONNECTED);
				return;
			}
			if (state.status == STATUS_INITIATED_FROM_REMOTE) {
				Log.i(TAG, "Incoming connection already initiated by " + remoteAddressName + ". Skipping outgoing request.");
				NodeTransientStateRepository.getInstance().updateTransientNodeState(remoteAddressName, NodeState.ON_CONNECTING);
				return;
			}
		}

		Log.d(TAG, "Requesting connection to " + remoteAddressName + " (EndpointId: " + remoteEndpointId + ")");
		addressNameToEndpointId.put(remoteAddressName, remoteEndpointId); // Store the mapping early

		connectionsClient.requestConnection(this.localAddressName, remoteEndpointId, connectionLifecycleCallback);
		putState(remoteEndpointId, remoteAddressName, STATUS_INITIATED_FROM_HOST, NodeState.ON_CONNECTING); // Mark as pending
	}

	/**
	 * Accepts a connection request from a remote device.
	 *
	 * @param remoteEndpointId The ID of the remote endpoint to accept the connection from.
	 */
	public void acceptConnection(@NonNull String remoteEndpointId) {
		ConnectionState state = endpointStates.get(remoteEndpointId);
		if (state == null || state.status != STATUS_INITIATED_FROM_REMOTE) {
			Log.w(TAG, "Attempted to accept connection from " + remoteEndpointId + " which was not in incoming connections.");
			return;
		}

		Log.d(TAG, "Accepting connection from: " + remoteEndpointId);
		// Actual connection establishment/failure handled in onConnectionResult
		connectionsClient.acceptConnection(remoteEndpointId, payloadCallback);
	}

	/**
	 * Rejects a connection request from a remote device.
	 *
	 * @param remoteEndpointId The ID of the remote endpoint to reject the connection from.
	 */
	public void rejectConnection(@NonNull String remoteEndpointId) {
		ConnectionState state = endpointStates.get(remoteEndpointId);
		if (state != null && state.status == STATUS_INITIATED_FROM_REMOTE) {
			connectionsClient.rejectConnection(remoteEndpointId);
			Log.d(TAG, "Rejected connection from: " + remoteEndpointId);
			// addressNameToEndpointId will be cleaned up by onConnectionResult due to status failure
		} else {
			Log.w(TAG, "Attempted to reject connection from " + remoteEndpointId + " which was not in incoming connections.");
		}
	}

	/**
	 * Request disconnection from the remote device.
	 *
	 * @param remoteEndpointId The ID of the remote endpoint to disconnect from.
	 */
	public void disconnectFromEndpoint(@NonNull String remoteEndpointId) {
		ConnectionState state = endpointStates.get(remoteEndpointId);
		if (state != null && state.status == STATUS_CONNECTED) {
			connectionsClient.disconnectFromEndpoint(remoteEndpointId);
			Log.d(TAG, "Requested disconnection from: " + remoteEndpointId);
			// Cleanup maps will happen in onDisconnected callback
		} else {
			Log.w(TAG, "Attempted to disconnect from unknown/non-existent endpoint: " + remoteEndpointId);
		}
	}

	/**
	 * Starts discovering other devices advertising the same service ID.
	 */
	public void startDiscovery() {
		if (isDiscovering()) {
			Log.i(TAG, "Already discovering, skipping startDiscovery call.");
			return;
		}
		DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder()
				.setStrategy(STRATEGY).build();

		connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
				.addOnSuccessListener(unused -> {
					Log.d(TAG, "Discovering started successfully!");
					isDiscovering = true;
				})
				.addOnFailureListener(e -> {
					if (e.getMessage() != null && e.getMessage().contains("STATUS_ALREADY_DISCOVERING")) {
						Log.d(TAG, "Discovering started successfully!");
						isDiscovering = true;
						return;
					}
					if (!isDiscovering) {
						Log.w(TAG, "Discovering failed!", e);
					}
				});
	}

	/**
	 * Stops discovering devices.
	 */
	public void stopDiscovery() {
		if (!isDiscovering) {
			Log.i(TAG, "Not discovering, skipping stopDiscovery call.");
			return;
		}
		connectionsClient.stopDiscovery();
		isDiscovering = false;
		Log.d(TAG, "Discovery stopped");
	}

	/**
	 * Stop all Nearby API interactions
	 */
	public void stopNearby() {
		if (!isAdvertising && !isDiscovering) {
			Log.i(TAG, "Not advertising or discovering, skipping stopNearby call.");
			return;
		}
		// Disconnect from all active connections
		Set<String> endpointsToDisconnect = endpointStates.entrySet().stream()
				.filter(entry -> entry.getValue().status == STATUS_CONNECTED)
				.map(Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toSet());
		Log.d(TAG, "Disconnecting from " + endpointsToDisconnect.size() + " active connections.");
		endpointsToDisconnect.forEach(connectionsClient::disconnectFromEndpoint);
		stopDiscovery();
		stopAdvertising();
		// Clear all maps
		endpointStates.clear();
		addressNameToEndpointId.clear();
		executorService.shutdownNow();
		Log.d(TAG, "All Nearby interactions stopped and connections reset.");
	}

	/**
	 * Encrypts a {@link NearbyMessageBody} and sends it to a specified recipient, assumed directly connected.
	 *
	 * @param endpointId           The ID of the endpoint to send the message to. If {@code null},
	 *                             it will be looked up using {@link #getLinkedEndpointId(String)}.
	 * @param recipientAddressName The address name of the recipient.
	 * @param plainMessageBody     The unencrypted {@link NearbyMessageBody} containing the application-level
	 * @param transmissionCallback A callback that receives the {@link Payload}
	 * @see #sendNearbyMessage(String, byte[], TransmissionCallback)
	 */
	protected void encryptAndSendInternal(
			@Nullable String endpointId, @NonNull String recipientAddressName,
			@NonNull NearbyMessageBody plainMessageBody, @NonNull TransmissionCallback transmissionCallback) {
		executorService.execute(() -> {
			try {
				final String endpoint = endpointId == null ? getLinkedEndpointId(recipientAddressName) : endpointId;
				if (endpoint == null) {
					Log.e(TAG, "Cannot send message to " + recipientAddressName + ": the endpointId not found.");
					transmissionCallback.onFailure(null, null); // Notify failure
					return;
				}
				CiphertextMessage ciphertextMessage = NearbySignalMessenger.getInstance().encrypt(plainMessageBody.toByteArray(), recipientAddressName);
				// Encapsulate the message
				NearbyMessage nearbyMessage = NearbyMessage.newBuilder().setExchange(false).setBody(ByteString.copyFrom(ciphertextMessage.serialize())).build();
				sendNearbyMessage(endpoint, nearbyMessage.toByteArray(), transmissionCallback);
				Log.d(TAG, "NearbyMessage (type: ENCRYPTED_DATA) sent to " + recipientAddressName);
			} catch (Exception e) {
				Log.e(TAG, "Error serializing or sending NearbyMessage to " + recipientAddressName, e);
				transmissionCallback.onFailure(null, e); // Notify failure
			}
		});
	}

	/**
	 * Sends a {@link NearbyMessageBody} to a specified recipient, attempting to use a direct Nearby Connections
	 * link if available, or initiating route discovery and forwarding through the mesh network otherwise.
	 * <p>
	 * This method first checks for an existing direct connection (represented by an {@code endpointId})
	 * to the {@code recipientAddressName}.
	 * <ul>
	 * <li>If a direct connection exists, the {@code plainMessageBody} is encrypted for the recipient
	 * (hop-by-hop encryption) and sent directly over Nearby Connections.</li>
	 * <li>If no direct connection is found, the method attempts to find an existing active route
	 * to the recipient via {@link NearbyRouteManager}.
	 * <ul>
	 * <li>If an active route exists, the {@code plainMessageBody} is immediately sent
	 * through that route using {@code NearbyRouteManager#sendMessageThroughRoute()}.</li>
	 * <li>If no active route is found, route discovery is initiated via
	 * {@link NearbyRouteManager#discoverRouteIfNeeded(String, java.util.function.Consumer, java.util.function.Consumer, String)}.
	 * Upon successful route discovery, the message will then be sent through the newly found route.</li>
	 * </ul>
	 * </li>
	 * </ul>
	 * Any errors during direct transmission or route handling will be reported via the {@code transmissionCallback}.
	 *
	 * @param plainMessageBody             The unencrypted {@link NearbyMessageBody} containing the application-level
	 *                                     message to be sent. This will be encrypted either directly (if neighbor)
	 *                                     or end-to-end (if routed).
	 * @param recipientAddressName         The {@code SignalProtocolAddress.name} of the final recipient.
	 * @param transmissionCallback         A callback that receives the {@link Payload}
	 *                                     (or {@code null} if direct transmission failed before payload created).
	 * @param routeTransmissionCallback    A callback that receives the {@link Payload}
	 *                                     or {@code null} if the attempt to send the message to the next
	 *                                     hop (direct connection or first hop of route). So it is used only if
	 *                                     the message is put on a route.
	 * @param onDiscoveryInitiatedCallback An optional {@link Consumer} callback that is invoked to
	 *                                     inform the caller if route discovery was initiated ({@code true})
	 *                                     or not needed/failed to initiate ({@code false}). This callback
	 *                                     is only relevant when a route needs to be discovered.
	 */
	protected void sendRoutableNearbyMessageInternal(
			@NonNull NearbyMessageBody plainMessageBody,
			@NonNull String recipientAddressName, @NonNull TransmissionCallback transmissionCallback,
			@NonNull TransmissionCallback routeTransmissionCallback,
			@Nullable Consumer<Boolean> onDiscoveryInitiatedCallback) {
		executorService.execute(() -> {
			final String endpointId = getLinkedEndpointId(recipientAddressName);
			if (endpointId == null) {
				Log.e(TAG, "There is no direct connection to address '" + recipientAddressName + "', we are going to attempt to find a route.");
				try {
					NearbyRouteManager nearbyRouteManager = NearbySignalMessenger.getInstance().getNearbyRouteManager();
					nearbyRouteManager.discoverRouteIfNeeded(recipientAddressName,
							routeWithUsage -> nearbyRouteManager.sendMessageThroughRoute(
									recipientAddressName, this.localAddressName, routeWithUsage, plainMessageBody, routeTransmissionCallback),
							onDiscoveryInitiatedCallback, localAddressName);
				} catch (Exception e) {
					Log.e(TAG, "Handling route for message transmission failed.", e);
					transmissionCallback.onFailure(null, e); // Notify failure
				}
				return;
			}

			encryptAndSendInternal(endpointId, recipientAddressName, plainMessageBody, transmissionCallback);
		});
	}

	/**
	 * Helper method to send a raw byte array payload over a connection.
	 * This is the method `NearbySignalMessenger` will call.
	 *
	 * @param endpointId The ID of the endpoint to send the message to.
	 * @param data       The raw byte array to send.
	 * @param callback   A callback that accepts the sent {@link Payload}
	 *                   The {@link Payload} argument will be null if the `data` was null or if the send operation failed before payload creation.
	 */
	protected void sendNearbyMessage(@NonNull String endpointId, @NonNull byte[] data, @Nullable TransmissionCallback callback) {
		if (data.length == 0) {
			Log.e(TAG, "Attempted to send null or empty data to " + endpointId);
			if (callback != null) callback.onFailure(null, null);
			return;
		}

		Payload payload = Payload.fromBytes(data); // Create payload from bytes
		connectionsClient.sendPayload(endpointId, payload)
				.addOnSuccessListener(unused -> {
					Log.d(TAG, "Payload ID " + payload.getId() + " sent successfully to " + endpointId);
					DataLog.logTransmissionEvent(TransmissionEventType.SEND, endpointId, payload.getId(), data.length, TransmissionStatus.SUCCESS);
					if (callback != null) {
						callback.onSuccess(payload);
					}
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to send Payload ID " + payload.getId() + " to " + endpointId, e);
					DataLog.logTransmissionEvent(TransmissionEventType.SEND, endpointId, payload.getId(), data.length, TransmissionStatus.FAILURE);
					if (callback != null) {
						callback.onFailure(payload, e);
					}
					// The device is probably disconnected, try force disconnection
					executorService.execute(() -> {
						ConnectionState state = endpointStates.remove(endpointId);
						if (state != null) {
							// Try disconnection
							disconnectFromEndpoint(endpointId);
						} else {
							String address = addressNameToEndpointId.entrySet().stream().filter(entry -> endpointId.equals(entry.getValue()))
									.map(Map.Entry::getKey).findFirst().orElse(null);
							Log.e(TAG, "Payload sending failed to endpoint=" + endpointId + " in unknown state and with extracted address=" + address);
							if (address != null) {
								addressNameToEndpointId.remove(address);
							}
						}
					});
				});
	}

	/**
	 * Callback method invoked by NearbyRouteManager when a route to a requested destination
	 * has been successfully found and established.
	 * This method is intended to notify higher-level application components that a path
	 * is now available for communication with the specified destination.
	 *
	 * @param destinationAddressName The SignalProtocolAddress.name of the destination node for which the route was found.
	 * @param routeEntry             The RouteEntry object representing the newly established route,
	 *                               containing details like the next hop and total hop count.
	 */
	public void onRouteFound(@NonNull String destinationAddressName, @NonNull RouteEntry routeEntry) {
		Log.d(TAG, destinationAddressName + ", route=" + routeEntry);
		NodeTransientStateRepository.getInstance().updateTransientNodeState(destinationAddressName, NodeState.ON_CONNECTED);
		NearbySignalMessenger.getInstance().onRouteFound(destinationAddressName);
	}

	/**
	 * Callback method invoked by NearbyRouteManager when a route discovery attempt
	 * to a requested destination has failed or completed without finding a route.
	 * This method notifies higher-level application components about the failure,
	 * providing the reason for the route not being found.
	 *
	 * @param requestUuid            The unique identifier of the route discovery request that failed.
	 * @param destinationAddressName The SignalProtocolAddress.name of the destination node for which the route discovery failed.
	 * @param finalStatus            The final status indicating why the route could not be found
	 *                               (e.g., NO_ROUTE_FOUND, TTL_EXPIRED, REQUEST_ALREADY_IN_PROGRESS).
	 */
	public void onRouteNotFound(@NonNull String requestUuid, @NonNull String destinationAddressName, @NonNull RouteResponseMessage.Status finalStatus) {
		Log.d(TAG, requestUuid + " " + destinationAddressName + " " + finalStatus);
		NodeTransientStateRepository.getInstance().updateTransientNodeState(destinationAddressName, NodeState.ON_DISCONNECTED);
		NearbySignalMessenger.getInstance().onRouteNotFound(destinationAddressName);
	}

	/**
	 * Callback method invoked by {@link NearbyRouteManager} when a routed message
	 * successfully reaches its final destination at this node.
	 * This method provides the original sender's identifier and the content of the message
	 * intended for this node. The {@code internalNearbyMessageBody} is already decrypted
	 * from its end-to-end encryption and represents the actual application-level message
	 * (e.g., chat message, personal info, ACK).
	 *
	 * @param originalSenderAddress     The {@code SignalProtocolAddress.name} of the original sender
	 *                                  who initiated this routed message.
	 * @param internalNearbyMessageBody The {@link NearbyMessageBody} containing the actual
	 *                                  message content and type, as sent by the original sender
	 *                                  and intended for this final destination. This object is
	 *                                  already end-to-end decrypted.
	 * @param payloadId                 The payload ID of the
	 */
	public void onRoutedMessageReceived(
			@NonNull String originalSenderAddress, @NonNull NearbyMessageBody internalNearbyMessageBody,
			long payloadId) {
		try {
			NearbySignalMessenger.getInstance().parseDecryptedMessage(internalNearbyMessageBody, originalSenderAddress, payloadId, true);
		} catch (Exception e) {
			Log.e(TAG, "Error processing encrypted message from " + originalSenderAddress, e);
		}
	}

	@IntDef({STATUS_INITIATED_FROM_REMOTE, STATUS_INITIATED_FROM_HOST, STATUS_CONNECTED, STATUS_DISCONNECTED, STATUS_FOUND})
	@Retention(RetentionPolicy.SOURCE)
	@interface ConnectivityStatus {
	}

	private record ConnectionState(@NonNull String addressName, @ConnectivityStatus int status) {
	}
}
