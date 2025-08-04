package org.sedo.satmesh.nearby.data;

import androidx.annotation.NonNull;

import com.google.android.gms.common.api.Status;

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
	 * @param endpointId        The Nearby Connections endpoint ID.
	 * @param deviceAddressName The Signal address name of the device.
	 * @param status            The status of the connection failure.
	 */
	void onConnectionFailed(@NonNull String endpointId, String deviceAddressName, Status status);

	/**
	 * Called when a device explicitly disconnects (or is lost).
	 *
	 * @param endpointId        The Nearby Connections endpoint ID.
	 * @param deviceAddressName The Signal address name of the disconnected device.
	 */
	void onDeviceDisconnected(@NonNull String endpointId, @NonNull String deviceAddressName);

	/**
	 * Handles the event when a new Nearby endpoint is found. This method is called only
	 * if the endpoint is freshly/newly found.
	 *
	 * @param endpointId        The ID of the discovered endpoint.
	 * @param deviceAddressName The Signal Protocol address name of the discovered endpoint.
	 */
	void onEndpointFound(@NonNull String endpointId, @NonNull String deviceAddressName);

	/**
	 * Handles the event when a Nearby endpoint is lost (goes out of range).
	 * This method primarily serves for logging and potentially notifying other components,
	 * as NearbyManager's own onDisconnected callback handles active connections.
	 *
	 * @param endpointId        The ID of the lost endpoint.
	 * @param deviceAddressName The Signal Protocol address name of the lost endpoint.
	 */
	void onEndpointLost(@NonNull String endpointId, @NonNull String deviceAddressName);
}