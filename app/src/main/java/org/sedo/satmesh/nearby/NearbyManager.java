package org.sedo.satmesh.nearby;

import android.content.Context;
import android.util.Log;

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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NearbyManager {

	private static final String TAG = "NearbyManager";
	private static final String SERVICE_ID = "org.sedo.satmesh.SECURE_MESSENGER"; // Unique service ID for the app
	private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
	private static volatile NearbyManager INSTANCE;

	/*
	 * Map to store connected endpoints: endpointId -> remote device's SignalProtocolAddress name
	 * These are endpoints for which a successful connection has been established.
	 */
	private final Map<String, String> connectedEndpointAddresses = new HashMap<>();
	/*
	 * Map to store pending endpoints: endpointId -> remote device's SignalProtocolAddress name
	 * These are endpoints for which WE initiated a connection request and are awaiting result.
	 */
	private final Map<String, String> pendingEndpointAddresses = new HashMap<>();

	/*
	 * Map to store endpoints that sent incoming connection: endpointId -> remote device's SignalProtocolAddress name
	 * These are endpoints for which the REMOTE device initiated a connection request to us.
	 */
	private final Map<String, String> incomingEndpointAddresses = new HashMap<>();
	/*
	 * Maps device' SignalProtocol address to its endpoint
	 * This map will contain only *currently connected endpoints, as determined by the
	 * `connectedEndpointAddresses` map. It will be cleared on disconnect.
	 */
	private final Map<String, String> addressNameToEndpointId = new HashMap<>();

	private final List<DeviceConnectionListener> deviceConnectionListeners = new CopyOnWriteArrayList<>();// Thread-safe list
	private final List<PayloadListener> payloadListeners = new CopyOnWriteArrayList<>();

	private final ConnectionsClient connectionsClient;
	// Use local address name as advertising name
	private final String localName;
	/**
	 * PayloadCallback handles incoming data payloads.
	 * It processes received bytes as Protobuf messages and dispatches them.
	 */
	private final PayloadCallback payloadCallback = new PayloadCallback() {
		@Override
		public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
			// Ensure payload has bytes and get the associated device name
			String deviceAddressName = connectedEndpointAddresses.get(endpointId);
			if (deviceAddressName == null) {
				// This could happen if a payload arrives just after a disconnect event
				Log.w(TAG, "Payload received from unknown or disconnected endpoint: " + endpointId);
				return;
			}
			Log.d(TAG, "Payload received from " + deviceAddressName + " (Endpoint ID: " + endpointId + ")");
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
					" from " + connectedEndpointAddresses.get(endpointId));
			payloadListeners.forEach(l -> l.onPayloadTransferUpdate(endpointId, update));
		}
	};
	/**
	 * ConnectionLifecycleCallback handles the lifecycle of connections.
	 * It initiates connection, processes results, and handles disconnections.
	 */
	private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
		@Override
		public void onConnectionInitiated(@NonNull String endpointId, ConnectionInfo connectionInfo) {
			Log.d(TAG, "Connection initiated with: " + endpointId + " Name: " + connectionInfo.getEndpointName());
			incomingEndpointAddresses.put(endpointId, connectionInfo.getEndpointName());
			addressNameToEndpointId.put(connectionInfo.getEndpointName(), endpointId); // Keep track of pending connections
			deviceConnectionListeners.forEach(listener -> listener.onConnectionInitiated(endpointId, connectionInfo.getEndpointName()));
			// NearbySignalMessenger, as listener, will now explicitly call acceptConnection, allowing for async processing.
		}

		@Override
		public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
			String remoteAddressName;
			// Determine if the connection was initiated by us or by the remote device
			if (pendingEndpointAddresses.containsKey(endpointId)) {
				// We initiated the connection
				remoteAddressName = pendingEndpointAddresses.remove(endpointId);
			} else if (incomingEndpointAddresses.containsKey(endpointId)) {
				// Remote device initiated the connection
				remoteAddressName = incomingEndpointAddresses.remove(endpointId);
			} else {
				Log.e(TAG, "onConnectionResult: Endpoint ID " + endpointId + " not found in pending or incoming maps. Orphaned connection result.");
				return; // Cannot proceed without a known remote address name
			}

			if (result.getStatus().isSuccess()) {
				Log.d(TAG, "Connection established with: " + remoteAddressName + " (EndpointId: " + endpointId + ")");
				// Store the mapping between endpointId and the remote device's SignalProtocolAddress name
				connectedEndpointAddresses.put(endpointId, remoteAddressName);
				addressNameToEndpointId.put(remoteAddressName, endpointId); // Update the core mapping

				deviceConnectionListeners.forEach(listener -> listener.onDeviceConnected(endpointId, remoteAddressName));
			} else {
				Log.e(TAG, "Connection failed with: " + remoteAddressName + " (EndpointId: " + endpointId + "). Status: " + result.getStatus().getStatusMessage());
				deviceConnectionListeners.forEach(l -> l.onConnectionFailed(remoteAddressName, result.getStatus()));
				// Clean up any remaining entries if connection failed
				addressNameToEndpointId.remove(remoteAddressName);
				connectedEndpointAddresses.remove(endpointId); // Ensure it's not mistakenly added
			}
		}

		@Override
		public void onDisconnected(@NonNull String endpointId) {
			Log.d(TAG, "Disconnected from: " + endpointId);
			String deviceAddress = connectedEndpointAddresses.remove(endpointId); // Remove from active connections
			if (deviceAddress != null) {
				deviceConnectionListeners.forEach(l -> l.onDeviceDisconnected(endpointId, deviceAddress));
				addressNameToEndpointId.remove(deviceAddress); // Remove from the core mapping
			} else {
				// This can happen if an endpoint was in pending/incoming and got disconnected before fully connecting
				// or if it was manually removed.
				String pendingAddress = pendingEndpointAddresses.remove(endpointId);
				String incomingAddress = incomingEndpointAddresses.remove(endpointId);
				String address = pendingAddress != null ? pendingAddress : incomingAddress;
				if (address != null) {
					Log.d(TAG, "Disconnected from pending/incoming endpoint: " + address + " (EndpointId: " + endpointId + ")");
					addressNameToEndpointId.remove(address);
					// Notify as a connection failed, as it never truly completed.
					deviceConnectionListeners.forEach(l -> l.onConnectionFailed(address, Status.RESULT_CANCELED));
				} else {
					Log.w(TAG, "onDisconnected: Endpoint ID " + endpointId + " not found in any active, pending, or incoming maps.");
				}
			}
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

	private NearbyManager(@NonNull Context context, @NonNull String localName) {
		this.connectionsClient = Nearby.getConnectionsClient(context);
		this.localName = localName;
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
		String name = connectedEndpointAddresses.get(endpointId);
		if (name == null) {
			name = pendingEndpointAddresses.get(endpointId);
		}
		if (name == null) {
			name = incomingEndpointAddresses.get(endpointId);
		}
		return name;
	}

	/**
	 * Gets the list of pending endpoint address names (initiated by us).
	 *
	 * @return An unmodifiable collection of SignalProtocolAddress names.
	 */
	@NonNull
	public Collection<String> getAllPendingAddressName() {
		return Collections.unmodifiableCollection(pendingEndpointAddresses.values());
	}

	/**
	 * Gets the list of currently connected endpoint address names.
	 *
	 * @return An unmodifiable collection of SignalProtocolAddress names.
	 */
	@NonNull
	public Collection<String> getAllConnectedAddressName() {
		return Collections.unmodifiableCollection(connectedEndpointAddresses.values());
	}

	/**
	 * Gets the list of all incoming connection endpoint address names (initiated by remote).
	 *
	 * @return An unmodifiable collection of SignalProtocolAddress names.
	 */
	@NonNull
	public Collection<String> getAllIncomingAddressName() {
		return Collections.unmodifiableCollection(incomingEndpointAddresses.values());
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
	 * Tests if there is, at call time an active connection with the specified
	 * Signal Protocol's address name.
	 *
	 * @param addressName the address name to test
	 * @return {@code true} if and only if the specified address name is bound to an endpoint
	 * in the map of connected node.
	 */
	public boolean isAddressDirectlyConnected(@Nullable String addressName) {
		return addressName != null && connectedEndpointAddresses.containsValue(addressName);
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
	 *
	 * @param onSuccess Callback for successful advertising start.
	 * @param onFailure Callback for advertising failure.
	 */
	public void startAdvertising(@Nullable Runnable onSuccess, @Nullable Consumer<Exception> onFailure) {
		if (isAdvertising()) {
			Log.i(TAG, "Already advertising, skipping startAdvertising call.");
			if (onSuccess != null) onSuccess.run(); // Still call success if already advertising
			return;
		}
		AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder()
				.setStrategy(STRATEGY).build();

		connectionsClient.startAdvertising(
						this.localName,
						SERVICE_ID,
						connectionLifecycleCallback,
						advertisingOptions
				).addOnSuccessListener(unused -> {
					Log.d(TAG, "Advertising started successfully");
					isAdvertising = true;
					if (onSuccess != null) onSuccess.run();
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to start advertising", e);
					if (onFailure != null) onFailure.accept(e);
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
	 * @param callback          Callback for success/failure of the connection request.
	 */
	public void requestConnection(@NonNull String remoteEndpointId, @NonNull String remoteAddressName,
	                              @Nullable BiConsumer<String, Boolean> callback) {
		if (connectedEndpointAddresses.containsKey(remoteEndpointId)) {
			Log.i(TAG, "Already connected to " + remoteAddressName + ". Skipping connection request.");
			if (callback != null) callback.accept(remoteEndpointId, true);
			return;
		}
		if (pendingEndpointAddresses.containsKey(remoteEndpointId)) {
			Log.i(TAG, "Connection request already pending for " + remoteAddressName + ". Skipping duplicate request.");
			if (callback != null)
				callback.accept(remoteEndpointId, true); // Treat as success for idempotence
			return;
		}
		if (incomingEndpointAddresses.containsKey(remoteEndpointId)) {
			Log.i(TAG, "Incoming connection already initiated by " + remoteAddressName + ". Skipping outgoing request.");
			if (callback != null)
				callback.accept(remoteEndpointId, true); // Treat as success for idempotence
			return;
		}

		Log.d(TAG, "Requesting connection to " + remoteAddressName + " (EndpointId: " + remoteEndpointId + ")");
		pendingEndpointAddresses.put(remoteEndpointId, remoteAddressName); // Mark as pending
		addressNameToEndpointId.put(remoteAddressName, remoteEndpointId); // Store the mapping early

		connectionsClient.requestConnection(this.localName,
						remoteEndpointId,
						connectionLifecycleCallback)
				.addOnSuccessListener(unused -> {
					Log.d(TAG, "Connection request sent to " + remoteEndpointId);
					// The actual connection success/failure is handled in connectionLifecycleCallback.onConnectionResult
					if (callback != null) callback.accept(remoteEndpointId, true);
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to send connection request to " + remoteEndpointId, e);
					pendingEndpointAddresses.remove(remoteEndpointId); // Remove from pending on immediate failure
					addressNameToEndpointId.remove(remoteAddressName); // Clean up
					if (callback != null) callback.accept(remoteEndpointId, false);
				});
	}

	/**
	 * Accepts a connection request from a remote device.
	 *
	 * @param remoteEndpointId The ID of the remote endpoint to accept the connection from.
	 * @param callback         Optional callback for success/failure of accepting.
	 */
	public void acceptConnection(@NonNull String remoteEndpointId, @Nullable BiConsumer<String, Boolean> callback) {
		if (!incomingEndpointAddresses.containsKey(remoteEndpointId)) {
			Log.w(TAG, "Attempted to accept connection from " + remoteEndpointId + " which was not in incoming connections.");
			if (callback != null) callback.accept(remoteEndpointId, false);
			return;
		}

		Log.d(TAG, "Accepting connection from: " + remoteEndpointId);
		connectionsClient.acceptConnection(remoteEndpointId, payloadCallback)
				.addOnSuccessListener(unused -> {
					Log.d(TAG, "Connection accepted with " + remoteEndpointId);
					// Actual connection establishment handled in onConnectionResult
					if (callback != null) callback.accept(remoteEndpointId, true);
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to accept connection from " + remoteEndpointId, e);
					incomingEndpointAddresses.remove(remoteEndpointId); // Clean up
					// addressNameToEndpointId will be cleaned by onConnectionResult if failure
					if (callback != null) callback.accept(remoteEndpointId, false);
				});
	}

	/**
	 * Rejects a connection request from a remote device.
	 *
	 * @param remoteEndpointId The ID of the remote endpoint to reject the connection from.
	 */
	public void rejectConnection(@NonNull String remoteEndpointId) {
		if (incomingEndpointAddresses.remove(remoteEndpointId) != null) {
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
		if (connectedEndpointAddresses.containsKey(remoteEndpointId) ||
				pendingEndpointAddresses.containsKey(remoteEndpointId) ||
				incomingEndpointAddresses.containsKey(remoteEndpointId)) {
			connectionsClient.disconnectFromEndpoint(remoteEndpointId);
			Log.d(TAG, "Requested disconnection from: " + remoteEndpointId);
			// Cleanup maps will happen in onDisconnected callback
		} else {
			Log.w(TAG, "Attempted to disconnect from unknown/non-existent endpoint: " + remoteEndpointId);
		}
	}

	/**
	 * Starts discovering other devices advertising the same service ID.
	 *
	 * @param onEndpointFoundCallback Called when a new endpoint is found (endpointId, endpointName).
	 * @param onEndpointLostCallback  Called when a previously found endpoint is no longer discoverable (endpointId, endpointName).
	 * @param onSuccess               Callback for successful discovery start.
	 * @param onFailure               Callback for discovery failure.
	 */
	public void startDiscovery(
			@Nullable BiConsumer<String, String> onEndpointFoundCallback,
			@Nullable BiConsumer<String, String> onEndpointLostCallback,
			@Nullable Runnable onSuccess,
			@Nullable Consumer<Exception> onFailure) {
		if (isDiscovering()) {
			Log.i(TAG, "Already discovering, skipping startDiscovery call.");
			if (onSuccess != null) onSuccess.run();
			return;
		}
		DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder()
				.setStrategy(STRATEGY)
				.build();

		EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
			@Override
			public void onEndpointFound(@NonNull String endpointId, DiscoveredEndpointInfo info) {
				Log.d(TAG, "Endpoint found: " + info.getEndpointName() + " with ID: " + endpointId);

				// Add to addressNameToEndpointId if not already present
				addressNameToEndpointId.putIfAbsent(info.getEndpointName(), endpointId);

				// Check if there's an existing or pending connection to avoid duplicate requests
				if (connectedEndpointAddresses.containsKey(endpointId) ||
						pendingEndpointAddresses.containsKey(endpointId) ||
						incomingEndpointAddresses.containsKey(endpointId)) {
					Log.i(TAG, "Endpoint " + info.getEndpointName() + " already connected or in pending state. Not requesting connection.");
				}

				// Notify about found endpoint
				if (onEndpointFoundCallback != null)
					onEndpointFoundCallback.accept(endpointId, info.getEndpointName());

				/*
				 * We don't automatically request connection here. `NearbySignalMessenger` or
				 * `SATMeshCommunicationService` will do that based on its session logic or
				 * explicit user action after discovering.
				 */
			}

			@Override
			public void onEndpointLost(@NonNull String endpointId) {
				String endpointName = getEndpointName(endpointId); // Try to get the name for logging/callback
				if (endpointName == null) {
					Log.w(TAG, "onEndpointLost: unable to locate the endpoint with ID: " + endpointId);
					return;
				}
				Log.d(TAG, "Endpoint lost: " + endpointName + " (ID: " + endpointId + ")");

				// Remove from relevant maps if it's not a currently connected endpoint
				// If it's connected, the onDisconnected callback will handle it.
				if (!connectedEndpointAddresses.containsKey(endpointId)) {
					pendingEndpointAddresses.remove(endpointId);
					incomingEndpointAddresses.remove(endpointId);
					addressNameToEndpointId.remove(endpointName);
				}

				// Notify about lost endpoint
				if (onEndpointLostCallback != null)
					onEndpointLostCallback.accept(endpointId, endpointName);
			}
		};

		connectionsClient.startDiscovery(
						SERVICE_ID,
						endpointDiscoveryCallback,
						discoveryOptions
				).addOnSuccessListener(unused -> {
					Log.d(TAG, "Discovery started successfully");
					isDiscovering = true;
					if (onSuccess != null) onSuccess.run();
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to start discovery", e);
					isDiscovering = false;
					if (onFailure != null) onFailure.accept(e);
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
		stopDiscovery();
		stopAdvertising();
		// Disconnect from all active connections
		for (String endpoint : new CopyOnWriteArrayList<>(connectedEndpointAddresses.keySet())) {
			// Iterate on copy to avoid ConcurrentModificationException
			connectionsClient.disconnectFromEndpoint(endpoint);
		}
		// Clear all maps
		pendingEndpointAddresses.clear();
		incomingEndpointAddresses.clear();
		connectedEndpointAddresses.clear();
		addressNameToEndpointId.clear();
		Log.d(TAG, "All Nearby interactions stopped and connections reset.");
	}

	/**
	 * Helper method to send a raw byte array payload over a connection.
	 * This is the method `NearbySignalMessenger` will call.
	 *
	 * @param endpointId The ID of the endpoint to send the message to.
	 * @param data       The raw byte array to send.
	 * @param callback   A {@link BiConsumer} that accepts the sent {@link Payload} and a boolean
	 *                   indicating success (true) or failure (false) of the send operation.
	 *                   The {@link Payload} argument will be null if the `data` was null or if the send operation failed before payload creation.
	 */
	public void sendNearbyMessage(@NonNull String endpointId, @NonNull byte[] data, @Nullable BiConsumer<Payload, Boolean> callback) {
		if (data.length == 0) {
			Log.e(TAG, "Attempted to send null or empty data to " + endpointId);
			if (callback != null) callback.accept(null, false);
			return;
		}

		Payload payload = Payload.fromBytes(data); // Create payload from bytes
		connectionsClient.sendPayload(endpointId, payload)
				.addOnSuccessListener(unused -> {
					Log.d(TAG, "Payload ID " + payload.getId() + " sent successfully to " + endpointId);
					if (callback != null) {
						callback.accept(payload, true);
					}
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to send Payload ID " + payload.getId() + " to " + endpointId, e);
					if (callback != null) {
						callback.accept(payload, false);
					}
				});
	}

	/**
	 * Listener for device connection and disconnection events.
	 * This is crucial for higher-level logic (e.g., Signal Protocol session management).
	 */
	public interface DeviceConnectionListener {
		/**
		 * Called when a connection is initiated with a device.
		 *
		 * @param endpointId        The Nearby Connections endpoint ID.
		 * @param deviceAddressName The Signal address name of the remote device (from endpointName or initial payload).
		 */
		void onConnectionInitiated(String endpointId, String deviceAddressName);

		/**
		 * Called when a device successfully connects.
		 *
		 * @param endpointId        The Nearby Connections endpoint ID.
		 * @param deviceAddressName The Signal address name of the connected device.
		 */
		void onDeviceConnected(String endpointId, String deviceAddressName);

		/**
		 * Called when a connection attempt fails or is disconnected prematurely.
		 *
		 * @param deviceAddressName The Signal address name of the device.
		 * @param status            The status of the connection failure.
		 */
		void onConnectionFailed(String deviceAddressName, Status status);

		/**
		 * Called when a device explicitly disconnects (or is lost).
		 *
		 * @param endpointId        The Nearby Connections endpoint ID.
		 * @param deviceAddressName The Signal address name of the disconnected device.
		 */
		void onDeviceDisconnected(String endpointId, String deviceAddressName);
	}

	/**
	 * Listener for Payload receiving events.
	 * Essential for processing incoming messages.
	 */
	public interface PayloadListener {
		/**
		 * Called when a payload is received.
		 *
		 * @param endpointId The ID of the endpoint from which the payload was received.
		 * @param payload    The received Payload object.
		 */
		void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload);

		/**
		 * Called for updates on payload transfer (progress, completion).
		 * Default implementation does nothing, can be overridden if detailed progress is needed.
		 *
		 * @param ignoredEndpointId The ID of the endpoint involved in the transfer.
		 * @param ignoredUpdate     The PayloadTransferUpdate object.
		 */
		default void onPayloadTransferUpdate(@NonNull String ignoredEndpointId, @NonNull PayloadTransferUpdate ignoredUpdate) {
		}
	}
}
