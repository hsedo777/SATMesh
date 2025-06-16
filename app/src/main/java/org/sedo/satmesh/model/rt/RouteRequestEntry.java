package org.sedo.satmesh.model.rt;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.Objects;

/**
 * Represents the global state of a specific route request being processed by a relay
 * node. This entity tracks the progress and status of a route discovery operation,
 * coordinating responses from parallel broadcasts to neighbors.
 * @author hovozounkou
 */
@Entity(tableName = "route_request_entry")
public class RouteRequestEntry {

	// The unique identifier of the route request (UUID of the RouteRequestMessage).
	@NonNull
	@PrimaryKey
	@ColumnInfo(name = "request_uuid")
	private String requestUuid;

	// The local identifier (Long) of the destination node for this request.
	@ColumnInfo(name = "destination_node_local_id")
	private Long destinationNodeLocalId;

	// The local identifier (Long) of the node that relayed this request to the current node.
	// This is crucial for backtracking the RouteResponseMessage when a route is found or fails.
	@ColumnInfo(name = "previous_hop_local_id")
	private Long previousHopLocalId;

	public RouteRequestEntry(@NonNull String requestUuid) {
		this.requestUuid = requestUuid;
	}

	// --- Getters ---

	@NonNull
	public String getRequestUuid() {
		return requestUuid;
	}

	public Long getDestinationNodeLocalId() {
		return destinationNodeLocalId;
	}

	public Long getPreviousHopLocalId() {
		return previousHopLocalId;
	}

	// --- Setters ---

	public void setRequestUuid(@NonNull String requestUuid) {
		this.requestUuid = requestUuid;
	}

	public void setDestinationNodeLocalId(Long destinationNodeLocalId) {
		this.destinationNodeLocalId = destinationNodeLocalId;
	}

	public void setPreviousHopLocalId(Long previousHopLocalId) {
		this.previousHopLocalId = previousHopLocalId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		RouteRequestEntry that = (RouteRequestEntry) o;
		return Objects.equals(requestUuid, that.requestUuid) && Objects.equals(destinationNodeLocalId, that.destinationNodeLocalId) && Objects.equals(previousHopLocalId, that.previousHopLocalId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(requestUuid, destinationNodeLocalId, previousHopLocalId);
	}

	@NonNull
	@Override
	public String toString() {
		return "RouteRequestEntry{" +
				"requestUuid='" + requestUuid + '\'' +
				", destinationNodeLocalId=" + destinationNodeLocalId +
				", previousHopLocalId=" + previousHopLocalId +
				'}';
	}
}
