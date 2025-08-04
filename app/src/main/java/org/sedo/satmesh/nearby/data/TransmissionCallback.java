package org.sedo.satmesh.nearby.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.nearby.connection.Payload;

/**
 * Callback interface for tracking the status of a data transmission between
 * two devices nearby.
 *
 * @author hsedo777
 */
public interface TransmissionCallback {

	/**
	 * A no-op implementation of {@link TransmissionCallback}.
	 */
	TransmissionCallback NULL_CALLBACK = new TransmissionCallback() {
		@Override
		public void onSuccess(@NonNull Payload payload) {
		}

		@Override
		public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
		}
	};

	/**
	 * Called when a payload transmission is successful.
	 *
	 * @param payload The successfully transmitted payload.
	 */
	void onSuccess(@NonNull Payload payload);

	/**
	 * Called when a payload transmission fails.
	 *
	 * @param payload The payload that failed to transmit (may be null if the failure occurred before the payload was created).
	 * @param cause   The exception that caused the failure (may be null).
	 */
	void onFailure(@Nullable Payload payload, @Nullable Exception cause);
}
