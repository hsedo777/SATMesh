package org.sedo.satmesh.ui;

/**
 * Listener for menu actions in the discussion context.
 */
public interface DiscussionMenuListener {
	/**
	 * Navigates to the search fragment.
	 *
	 * @param hostNodeId The ID of the host node.
	 */
	void moveToSearchFragment(Long hostNodeId);

	/**
	 * Navigates to the known nodes fragment.
	 *
	 * @param hostNodeId The ID of the host node.
	 */
	void moveToKnownNodesFragment(Long hostNodeId);

	/**
	 * Navigates to the settings fragment.
	 */
	void moveToSettingsFragment();

	/**
	 * Navigates to the QR code fragment.
	 */
	void moveToQrCodeFragment();
}
