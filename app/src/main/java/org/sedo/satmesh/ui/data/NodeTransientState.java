package org.sedo.satmesh.ui.data;

import java.util.Objects;

/**
 * This utility class is defined to map the transient state of {@link org.sedo.satmesh.model.Node}.
 */
public class NodeTransientState {
	public NodeState connectionState;
	/**
	 * Holds the current key exchange statuses.
	 * Value: true for success, false for failure, {@code null} if not defined.
	 */
	public Boolean keyExchangeStatus;
	/**
	 * Holds the current secure session initiation statuses.
	 * Value: true for success, false for failure, {@code null} if not defined.
	 */
	public Boolean sessionInitStatus;
	//public Boolean typingStatus; //later

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		NodeTransientState that = (NodeTransientState) o;
		return connectionState == that.connectionState && Objects.equals(keyExchangeStatus, that.keyExchangeStatus) && Objects.equals(sessionInitStatus, that.sessionInitStatus);
	}

	@Override
	public int hashCode() {
		return Objects.hash(connectionState, keyExchangeStatus, sessionInitStatus);
	}
}
