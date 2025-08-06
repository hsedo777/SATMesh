package org.sedo.satmesh.ui;

/**
 * Interface for listeners that want to be notified when a user's display name changes.
 * This is typically used by components that display or manage user information and
 * need to react to updates in the user's preferred display name.
 */
public interface UserDisplayNameListener {

	/**
	 * Called when the user's display name has been successfully changed.
	 *
	 * @param oldDisplayName The previous display name of the user.
	 * @param newDisplayName The new display name that the user has set.
	 */
	void onUserDisplayNameChanged(String oldDisplayName, String newDisplayName);
}
