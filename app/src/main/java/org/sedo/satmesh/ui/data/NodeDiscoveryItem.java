package org.sedo.satmesh.ui.data;

import androidx.annotation.NonNull;

import org.sedo.satmesh.model.Node;

import java.util.Objects;

/**
 * A data class representing a single entry of node i fragment `NearbyDiscoveryFragment`.
 */
public class NodeDiscoveryItem {
	@NonNull public Node node;
	@NonNull public NodeState state;
	public boolean isSecured;

	public NodeDiscoveryItem(@NonNull Node node, @NonNull NodeState state, boolean isSecured) {
		this.node = node;
		this.state = state;
		this.isSecured = isSecured;
	}

	@NonNull
	public String getAddressName() {
		return node.getAddressName();
	}

	@NonNull
	public String getNonNullName() {
		return node.getNonNullName();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		NodeDiscoveryItem item = (NodeDiscoveryItem) o;
		return Objects.equals(node, item.node) && state == item.state;
	}

	@Override
	public int hashCode() {
		return Objects.hash(node, state);
	}

	@NonNull
	@Override
	public String toString() {
		return "NodeDiscoveryItem{" +
				"node=" + node +
				", state=" + state +
				'}';
	}
}