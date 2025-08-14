package org.sedo.satmesh.ui;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Listener for menu actions in the discussion context.
 */
public interface DiscussionMenuListener extends QrCodeGenerationListener {
	/**
	 * Navigates to the search fragment.
	 */
	void moveToSearchFragment();

	/**
	 * Navigates to the known nodes fragment.
	 */
	void moveToKnownNodesFragment();

	/**
	 * Navigates to the settings fragment.
	 */
	void moveToSettingsFragment();

	/**
	 * Navigates to the QR code fragment.
	 */
	void moveToQrCodeFragment(@Nullable Bundle extra);

	@Override
	default void generatedAcknowledgeTo(@NonNull String recipientAddressName) {
		Bundle extra = new Bundle();
		extra.putString(QrCodeGenerationListener.RECIPIENT_ADDRESS_NAME, recipientAddressName);
		moveToQrCodeFragment(extra);
	}

	/**
	 * Navigates to the import QR code fragment.
	 */
	void moveToImportQrCodeFragment();
}
