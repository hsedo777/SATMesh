package org.sedo.satmesh.ui.data;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * This utility class is defined to map the transient state of {@link org.sedo.satmesh.model.Node}.
 */
public class NodeTransientState {
	public NodeState connectionState;
	//public Boolean typingStatus; //later

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		NodeTransientState that = (NodeTransientState) o;
		return connectionState == that.connectionState;
	}

	@Override
	public int hashCode() {
		return Objects.hash(connectionState);
	}

	@NonNull
	@Override
	public String toString() {
		return "NodeTransientState{" +
				"connectionState=" + connectionState +
				'}';
	}
}
