package org.sedo.satmesh.ui;

import androidx.annotation.NonNull;

/**
 * Listener for QR code generation events.
 *
 * @author hsedo777
 */
public interface QrCodeGenerationListener {

	/**
	 * Key for the recipient address name in an bundle.
	 */
	String RECIPIENT_ADDRESS_NAME = "recipient_address_name";

	/**
	 * Called when an acknowledgment QR code is to be generated for a recipient.
	 *
	 * @param recipientAddressName The address name of the recipient.
	 */
	void generatedAcknowledgeTo(@NonNull String recipientAddressName);
}
