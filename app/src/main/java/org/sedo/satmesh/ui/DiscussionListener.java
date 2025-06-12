package org.sedo.satmesh.ui;

import androidx.annotation.NonNull;

import org.sedo.satmesh.model.Node;

public interface DiscussionListener {
	void discussWith(@NonNull Node remoteNode);
}
