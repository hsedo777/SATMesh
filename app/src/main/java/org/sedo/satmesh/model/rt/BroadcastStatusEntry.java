package org.sedo.satmesh.model.rt;

import static androidx.room.ForeignKey.CASCADE;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

import java.util.Objects;

/**
 * Represents the status of a specific route request broadcast message to one of the immediate neighbors.
 * This entity tracks whether a particular neighbor has responded with an "already in progress" status,
 * which is critical for specific priority handling in the routing algorithm.
 * It forms a composite primary key with 'request_uuid' and 'neighbor_node_local_id' to uniquely
 * identify the status of a specific request sent to a specific neighbor.
 */
@Entity(tableName = "broadcast_status_entry",
		primaryKeys = {"request_uuid", "neighbor_node_local_id"}, // Composite Primary Key
		foreignKeys = @ForeignKey(
				entity = RouteRequestEntry.class,
				parentColumns = "request_uuid", // Refers to the primary key in RouteRequestEntry
				childColumns = "request_uuid",
				onDelete = CASCADE // If the parent RouteRequestEntry is deleted, associated BroadcastStatusEntries are also deleted.
		),
		indices = {
				// Index on request_uuid to optimize lookups for all broadcast statuses related to a specific request.
				@Index(value = {"request_uuid"}),
				// Index on neighbor_node_local_id for lookups related to a specific neighbor.
				@Index(value = {"neighbor_node_local_id"})
		})
public class BroadcastStatusEntry {

	/*
	 * The unique identifier of the route request to which this broadcast status belongs.
	 * This is part of the composite primary key and links to RouteRequestEntry.
	 */
	@NonNull
	@ColumnInfo(name = "request_uuid")
	private final String requestUuid;

	/*
	 * The local identifier (Long) of the immediate neighbor node to which the RouteRequestMessage
	 * was broadcasted. This is also part of the composite primary key.
	 */
	@ColumnInfo(name = "neighbor_node_local_id")
	private final long neighborNodeLocalId;

	/*
	 * A boolean flag indicating if this specific broadcast to this neighbor
	 * has resulted in a "REQUEST_ALREADY_IN_PROGRESS" response from that neighbor.
	 * True if such a response was received; false otherwise (meaning pending, or
	 * a negative response that would typically lead to deletion of this entry).
	 */
	@ColumnInfo(name = "is_pending_response_in_progress")
	private boolean isPendingResponseInProgress;

	/**
	 * Default constructor. Required by Room for entity instantiation.
	 */
	public BroadcastStatusEntry(@NonNull String requestUuid, long neighborNodeLocalId) {
		// Default to false for the boolean flag
		this.isPendingResponseInProgress = false;
		this.requestUuid = requestUuid;
		this.neighborNodeLocalId = neighborNodeLocalId;
	}

	// Getters & setters

	@NonNull
	public String getRequestUuid() {
		return requestUuid;
	}

	public long getNeighborNodeLocalId() {
		return neighborNodeLocalId;
	}

	public boolean isPendingResponseInProgress() {
		return isPendingResponseInProgress;
	}

	public void setPendingResponseInProgress(boolean pendingResponseInProgress) {
		isPendingResponseInProgress = pendingResponseInProgress;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		BroadcastStatusEntry that = (BroadcastStatusEntry) o;
		return Objects.equals(requestUuid, that.requestUuid) &&
				Objects.equals(neighborNodeLocalId, that.neighborNodeLocalId) &&
				Objects.equals(isPendingResponseInProgress, that.isPendingResponseInProgress);
	}

	@Override
	public int hashCode() {
		return Objects.hash(requestUuid, neighborNodeLocalId, isPendingResponseInProgress);
	}

	@NonNull
	@Override
	public String toString() {
		return "BroadcastStatusEntry{" +
				"requestUuid='" + requestUuid + '\'' +
				", neighborNodeLocalId=" + neighborNodeLocalId +
				", isPendingResponseInProgress=" + isPendingResponseInProgress +
				'}';
	}
}
