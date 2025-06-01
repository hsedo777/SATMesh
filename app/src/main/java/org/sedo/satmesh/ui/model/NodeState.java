package org.sedo.satmesh.ui.model;

import androidx.annotation.ColorRes;
import org.sedo.satmesh.R;

public enum NodeState {
	ON_ENDPOINT_FOUND(R.color.node_state_found),
	ON_CONNECTED(R.color.node_state_connected),
	ON_ENDPOINT_LOST(R.color.node_state_lost),
	ON_DISCONNECTED(R.color.node_state_disconnected),
	ON_CONNECTION_INITIATED(R.color.node_state_initiated),
	ON_CONNECTION_FAILED(R.color.node_state_failed);

	@ColorRes
	private final int colorResId;

	NodeState(@ColorRes int colorResId) {
		this.colorResId = colorResId;
	}

	public int getColorResId() {
		return colorResId;
	}
}
