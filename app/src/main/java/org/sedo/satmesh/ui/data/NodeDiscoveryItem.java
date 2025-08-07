package org.sedo.satmesh.ui.data;

import androidx.annotation.NonNull;

import org.sedo.satmesh.model.Node;

/**
 * A data class representing a single entry of node in fragment `NearbyDiscoveryFragment`.
 */
public record NodeDiscoveryItem(@NonNull Node node, @NonNull NodeState state, boolean isSecured) {

	@NonNull
	public String getAddressName() {
		return node.getAddressName();
	}

	@NonNull
	public String getNonNullName() {
		return node.getNonNullName();
	}
}