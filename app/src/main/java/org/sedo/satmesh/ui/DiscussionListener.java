package org.sedo.satmesh.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.sedo.satmesh.model.Node;

public interface DiscussionListener {
	default void discussWith(@NonNull Node remoteNode, boolean removePreviousFragment){
		discussWith(remoteNode, removePreviousFragment, null);
	}

	void discussWith(@NonNull Node remoteNode, boolean removePreviousFragment, @Nullable Long messageIdToScrollTo);
}
