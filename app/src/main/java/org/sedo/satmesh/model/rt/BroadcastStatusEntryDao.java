package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

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
	 *
	 * @param broadcastStatusEntry The BroadcastStatusEntry to insert.
	 * @return The row ID of the inserted or replaced entry.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	Long insert(BroadcastStatusEntry broadcastStatusEntry); // Return type changed to Long for composite PK inserts

	/**
	 * Updates an existing BroadcastStatusEntry.
	 *
	 * @param broadcastStatusEntry The BroadcastStatusEntry to update.
	 * @return The number of rows updated (should be 1 for a successful update).
	 */
	@Update
	int update(BroadcastStatusEntry broadcastStatusEntry);

	/**
	 * Deletes a BroadcastStatusEntry.
	 *
	 * @param broadcastStatus The BroadcastStatusEntry to delete.
	 */
	@Delete
	void delete(BroadcastStatusEntry broadcastStatus);

	/**
	 * Deletes all BroadcastStatusEntry based on the route request UUID.
	 *
	 * @param requestUuid The UUID of the route request.
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM broadcast_status_entry WHERE request_uuid = :requestUuid")
	int deleteAllByRequestUuid(String requestUuid);

	/**
	 * Gets a specific BroadcastStatusEntry based on its composite primary key.
	 *
	 * @param requestUuid         The UUID of the route request.
	 * @param neighborNodeLocalId The local ID of the neighbor.
	 * @return The matched {@link BroadcastStatusEntry}
	 */
	@Query("SELECT *  FROM broadcast_status_entry WHERE request_uuid = :requestUuid AND neighbor_node_local_id = :neighborNodeLocalId")
	BroadcastStatusEntry findBroadcastStatus(String requestUuid, Long neighborNodeLocalId);

	/**
	 * Checks if there's any BroadcastStatusEntry for a given request UUID
	 * with progress pending value to {@code pendingInProgress}.
	 * This is used to determine if a priority "already in progress" response has been received or not.
	 *
	 * @param requestUuid       The UUID of the route request.
	 * @param pendingInProgress Value of the progress pending to match.
	 * @return True if at least one such entry exists, false otherwise.
	 */
	@Query("SELECT COUNT(*) > 0 FROM broadcast_status_entry WHERE request_uuid = :requestUuid AND is_pending_response_in_progress = :pendingInProgress")
	boolean hasResponseInProgressState(String requestUuid, boolean pendingInProgress);
}
