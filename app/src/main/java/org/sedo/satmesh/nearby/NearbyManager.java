package org.sedo.satmesh.nearby;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

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

import org.sedo.satmesh.proto.NearbyMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class NearbyManager {

	private static final String TAG = "NearbyManager";
	private static final String SERVICE_ID = "org.sedo.satmesh.SECURE_MESSENGER"; // Unique service ID for the app
	private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

	// Map to store connected endpoints: endpointId -> remote device's SignalProtocolAddress name
	private final Map<String, String> connectedEndpointAddresses = new HashMap<>();

	// Map to store pending endpoints: endpointId -> remote device's SignalProtocolAddress name
	private final Map<String, String> pendingEndpointAddresses = new HashMap<>();

	private final List<DeviceConnectionListener> deviceConnectionListeners = new ArrayList<>();
	private final List<AdvertisingListener> advertisingListeners = new ArrayList<>();
	private final List<DiscoveringListener> discoveringListeners = new ArrayList<>();
	private final List<PayloadListener> payloadListeners = new ArrayList<>();

	private final ConnectionsClient connectionsClient;
	// Use local address name as advertising name
	private final String localName;
	/**
	 * True if we are advertising.
	 */
	private boolean isAdvertising = false;
	/**
	 * True if we are discovering.
	 */
	private boolean isDiscovering = false;
	/**
	 * True if we are asking a discovered device to connect to us. While we ask, we cannot ask another
	 * device.
	 */
	//private boolean isConnecting = false;

	/**
	 * PayloadCallback handles incoming data payloads.
	 * It processes received bytes as Protobuf messages and dispatches them.
	 */
	private final PayloadCallback payloadCallback = new PayloadCallback() {
		@Override
		public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
			payloadListeners.forEach(l -> l.onPayloadReceived(endpointId, payload));
		}

		@Override
		public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
			/*
			 * Handle payload transfer updates (e.g., progress, completion).
			 * For small messages like these, it might not be strictly necessary,
			 * but can be useful for larger file transfers.
			 */
			Log.d(TAG, "Payload transfer update: " + update.getPayloadId() + " " + update.getStatus());
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
			pendingEndpointAddresses.put(endpointId, connectionInfo.getEndpointName());
			// Automatically accept all connections for simplicity in this example.
			// In a production app, you might want to show a dialog to the user for confirmation.
			connectionsClient.acceptConnection(endpointId, payloadCallback);
			deviceConnectionListeners.forEach(listener -> listener.onConnectionInitiated(endpointId, connectionInfo.getEndpointName()));
		}

		@Override
		public void onConnectionResult(@NonNull String endpointId, ConnectionResolution result) {
			String remoteAddressName = pendingEndpointAddresses.remove(endpointId);
			if (result.getStatus().isSuccess()) {
				Log.d(TAG, "Connection established with: " + endpointId);
				// Store the mapping between endpointId and the remote device's SignalProtocolAddress name
				// The endpointName provided by Nearby Connections is the remote user's SignalProtocolAddress.name
				connectedEndpointAddresses.put(endpointId, remoteAddressName);

				deviceConnectionListeners.forEach(listener -> listener.onDeviceConnected(endpointId, remoteAddressName));
			} else {
				Log.e(TAG, "Connection failed with: " + endpointId + " Status: " + result.getStatus().getStatusMessage());
				deviceConnectionListeners.forEach(l -> l.onConnectionFailed(remoteAddressName, result.getStatus()));
			}
		}

		@Override
		public void onDisconnected(@NonNull String endpointId) {
			Log.d(TAG, "Disconnected from: " + endpointId);
			String deviceAddress = connectedEndpointAddresses.remove(endpointId);
			if (deviceAddress != null) {
				deviceConnectionListeners.forEach(l -> l.onDeviceDisconnected(endpointId, deviceAddress));
			}
		}
	};

	private NearbyManager(@NonNull Context context, @NonNull String localName) {
		this.connectionsClient = Nearby.getConnectionsClient(context);
		this.localName = localName;
	}

	private static NearbyManager INSTANCE;

	public NearbyManager getInstance(@NonNull Context context, @NonNull String localName){
		if (INSTANCE == null){
			synchronized (NearbyManager.class){
				if (INSTANCE == null){
					INSTANCE = new NearbyManager(context, localName);
				}
			}
		}
		return INSTANCE;
	}

	/**
	 * Adds the listener for device connection/disconnection events.
	 *
	 * @param listener The implementation of DeviceConnectionListener.
	 */
	public void addDeviceConnectionListener(DeviceConnectionListener listener) {
		this.deviceConnectionListeners.add(listener);
	}

	/**
	 * Adds the listener for advertising events.
	 *
	 * @param listener The implementation of {@link AdvertisingListener}.
	 */
	public void addAdvertisingListener(AdvertisingListener listener) {
		this.advertisingListeners.add(listener);
	}

	/**
	 * Adds the listener for discovering events.
	 *
	 * @param listener The implementation of {@link DiscoveringListener}.
	 */
	public void addPayloadListener(DiscoveringListener listener) {
		this.discoveringListeners.add(listener);
	}

	/**
	 * Adds the listener for payload receiving events.
	 *
	 * @param listener The implementation of {@link PayloadListener}.
	 */
	public void addPayloadListener(PayloadListener listener) {
		this.payloadListeners.add(listener);
	}

	/**
	 * Removes the listener for discovering events.
	 *
	 * @param listener The implementation of {@link PayloadListener}.
	 */
	public void removePayloadListener(PayloadListener listener) {
		this.payloadListeners.remove(listener);
	}

	public String getEndpointName(String endpointId){
		String endpointName = connectedEndpointAddresses.get(endpointId);
		if (endpointName == null){
			endpointName = pendingEndpointAddresses.get(endpointId);
		}
		return endpointName;
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
					advertisingListeners.forEach(AdvertisingListener::onAdvertisingStarted);
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to start advertising", e);
					advertisingListeners.forEach(l -> l.onAdvertisingFailed(e));
				});
	}

	/**
	 * Stops advertising.
	 */
	public void stopAdvertising() {
		connectionsClient.stopAdvertising();
		isAdvertising = false;
		Log.d(TAG, "Advertising stopped");
	}

	public boolean isDiscovering() {
		return isDiscovering;
	}

	/**
	 * Starts discovering other devices advertising the same service ID.
	 */
	public void startDiscovery() {
		if (isDiscovering()) {
			Log.i(TAG, "Already discovering !");
			return;
		}
		DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder()
				.setStrategy(STRATEGY)
				.build();

		EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
			@Override
			public void onEndpointFound(@NonNull String endpointId, DiscoveredEndpointInfo info) {
				Log.d(TAG, "Endpoint found: " + info.getEndpointName() + " with ID: " + endpointId);
				// Request connection to the discovered endpoint.
				// In production, you might want to filter or prompt the user before connecting.
				connectionsClient.requestConnection(
								NearbyManager.this.localName, // Local device's advertising name
								endpointId,
								connectionLifecycleCallback
						).addOnSuccessListener(unused -> Log.d(TAG, "Requested connection to " + info.getEndpointName()))
						.addOnFailureListener(e -> Log.e(TAG, "Failed to request connection to " + info.getEndpointName(), e));
				discoveringListeners.forEach(l -> l.onEndpointFound(endpointId, info.getEndpointName()));
			}

			@Override
			public void onEndpointLost(@NonNull String endpointId) {
				Log.d(TAG, "Endpoint lost: " + endpointId);
				discoveringListeners.forEach(l -> l.onEndpointLost(endpointId));
			}
		};

		connectionsClient.startDiscovery(
						SERVICE_ID,
						endpointDiscoveryCallback,
						discoveryOptions
				).addOnSuccessListener(unused -> {
					Log.d(TAG, "Discovery started successfully");
					isDiscovering = true;
					discoveringListeners.forEach(DiscoveringListener::onDiscoveringStarted);
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to start discovery", e);
					isDiscovering = false;
					discoveringListeners.forEach(l -> l.onDiscoveringFailed(e));
				});
	}

	/**
	 * Stops discovering devices.
	 */
	public void stopDiscovery() {
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
		for (String endpoint : connectedEndpointAddresses.keySet()) {
			connectionsClient.disconnectFromEndpoint(endpoint);
		}
		pendingEndpointAddresses.clear();
		connectedEndpointAddresses.clear();
	}

	/**
	 * Helper method to send a Protobuf NearbyMessage over a connection.
	 *
	 * @param endpointId The ID of the endpoint to send the message to.
	 * @param message    The NearbyMessage Protobuf object to send.
	 * @param callback   consume the payload, after attempt to send the message, and a boolean
	 *                   specifying success or failure of the message.
	 */
	public void sendNearbyMessage(@NonNull String endpointId, @NonNull NearbyMessage message, BiConsumer<Payload, Boolean> callback) {
		Payload payload = Payload.fromBytes(message.toByteArray());
		connectionsClient.sendPayload(endpointId, payload)
				.addOnSuccessListener(unused -> {
					Log.d(TAG, "Payload sent to " + endpointId);
					if (callback != null) {
						callback.accept(payload, true);
					}
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to send payload to " + endpointId, e);
					if (callback != null) {
						callback.accept(payload, false);
					}
				});
	}

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
		 * @param deviceAddressName The Signal address name of the connected device.
		 */
		void onDeviceConnected(String endpointId, String deviceAddressName);

		/**
		 * Called when a connection attempt fails.
		 *
		 * @param deviceAddressName The Signal address name of the device.
		 * @param status            The status of the connection failure.
		 */
		void onConnectionFailed(String deviceAddressName, Status status);

		/**
		 * Called when a device disconnects.
		 *
		 * @param deviceAddressName The Signal address name of the disconnected device.
		 */
		void onDeviceDisconnected(String endpointId, String deviceAddressName);
	}

	/**
	 * Listening for advertising operations.
	 */
	public interface AdvertisingListener {
		/**
		 * Called when advertising has successfully started.
		 */
		void onAdvertisingStarted();

		/**
		 * Called when advertising fails to start.
		 *
		 * @param e The exception that caused the failure.
		 */
		void onAdvertisingFailed(Exception e);
	}

	/**
	 * Listening for discovery operations.
	 */
	public interface DiscoveringListener {
		/**
		 * Called when discovery has successfully started.
		 */
		void onDiscoveringStarted();

		/**
		 * Called when discovery fails to start.
		 *
		 * @param e The exception that caused the failure.
		 */
		void onDiscoveringFailed(Exception e);

		/**
		 * Called when a new endpoint is found.
		 *
		 * @param endpointId   The unique ID of the discovered endpoint.
		 * @param endpointName The display name of the discovered endpoint.
		 */
		void onEndpointFound(String endpointId, String endpointName);

		/**
		 * Called when an previously found endpoint is no longer discoverable.
		 *
		 * @param endpointId The unique ID of the lost endpoint.
		 */
		void onEndpointLost(String endpointId);
	}

	/**
	 * Listen for Payload receiving
	 */
	public interface PayloadListener{
		void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload);

		default void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update){}
	}
}
