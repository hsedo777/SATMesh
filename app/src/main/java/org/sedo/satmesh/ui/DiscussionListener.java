package org.sedo.satmesh.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.sedo.satmesh.model.Node;

/**
 * Listener for discussion-related actions.
 */
public interface DiscussionListener {
	/**
	 * Starts a discussion with the given remote node.
	 *
	 * @param remoteNode             The remote node to discuss with.
	 * @param removePreviousFragment True if the previous fragment should be removed, false otherwise.
	 */
	default void discussWith(@NonNull Node remoteNode, boolean removePreviousFragment) {
		discussWith(remoteNode, removePreviousFragment, null);
	}

	/**
	 * Starts a discussion with the given remote node.
	 *
	 * @param remoteNode             The remote node to discuss with.
	 * @param removePreviousFragment True if the previous fragment should be removed, false otherwise.
	 * @param messageIdToScrollTo    The ID of the message to scroll to, or null if no scrolling is needed.
	 */
	void discussWith(@NonNull Node remoteNode, boolean removePreviousFragment, @Nullable Long messageIdToScrollTo);
}
