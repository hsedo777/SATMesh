package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object (DAO) for the BroadcastStatusEntry entity.
 * Manages the status of route request messages broadcasted to individual neighbors.
 */
@Dao
public interface BroadcastStatusEntryDao {

	/**
	 * Inserts a new BroadcastStatusEntry. Given its composite primary key,
	 * OnConflictStrategy.REPLACE is suitable for updating an existing status for a
	 * (request_uuid, neighbor_node_local_id) pair.
	 * @param broadcastStatusEntry The BroadcastStatusEntry to insert.
	 * @return The row ID of the inserted or replaced entry.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	Long insert(BroadcastStatusEntry broadcastStatusEntry); // Return type changed to Long for composite PK inserts

	/**
	 * Updates an existing BroadcastStatusEntry.
	 * @param broadcastStatusEntry The BroadcastStatusEntry to update.
	 * @return The number of rows updated (should be 1 for a successful update).
	 */
	@Update
	int update(BroadcastStatusEntry broadcastStatusEntry);

	/**
	 * Deletes a specific BroadcastStatusEntry based on its composite primary key.
	 * This is used when the status for a specific request to a specific neighbor is no longer needed.
	 * @param requestUuid The UUID of the route request.
	 * @param neighborNodeLocalId The local ID of the neighbor.
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM broadcast_status_entry WHERE request_uuid = :requestUuid AND neighbor_node_local_id = :neighborNodeLocalId")
	int delete(String requestUuid, Long neighborNodeLocalId);

	/**
	 * Retrieves all BroadcastStatusEntries associated with a specific route request UUID.
	 * This is crucial for a node to track the responses from all its neighbors for a given request.
	 * @param requestUuid The UUID of the route request.
	 * @return A list of BroadcastStatusEntry objects for the specified request.
	 */
	@Query("SELECT * FROM broadcast_status_entry WHERE request_uuid = :requestUuid")
	List<BroadcastStatusEntry> getBroadcastStatusesForRequest(String requestUuid);

	/**
	 * Checks if there's any BroadcastStatusEntry for a given request UUID
	 * where the 'is_pending_response_in_progress' flag is true.
	 * This is used to determine if a priority "already in progress" response has been received.
	 * @param requestUuid The UUID of the route request.
	 * @return True if at least one such entry exists, false otherwise.
	 */
	@Query("SELECT COUNT(*) > 0 FROM broadcast_status_entry WHERE request_uuid = :requestUuid AND is_pending_response_in_progress = 1")
	boolean hasPendingResponseInProgress(String requestUuid);
}
