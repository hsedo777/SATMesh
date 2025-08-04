package org.sedo.satmesh.nearby.data;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;

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