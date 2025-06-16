package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

/**
 * Data Access Object (DAO) for the RouteRequestEntry entity.
 * Manages the state of active route discovery requests originating from or passing through this node.
 */
@Dao
public interface RouteRequestEntryDao {

	/**
	 * Inserts a new RouteRequestEntry into the database.
	 * Given that 'request_uuid' is the primary key, conflicts are handled by replacing the existing entry.
	 * This is useful if a request is re-initiated or its state is conceptually updated.
	 * @param routeRequestEntry The RouteRequestEntry to insert.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insert(RouteRequestEntry routeRequestEntry); // Return type changed to void as primary key is String

	/**
	 * Updates an existing RouteRequestEntry.
	 * @param routeRequestEntry The RouteRequestEntry to update.
	 * @return The number of rows updated (should be 1 for a successful update).
	 */
	@Update
	int update(RouteRequestEntry routeRequestEntry);

	/**
	 * Deletes a specific RouteRequestEntry by its unique request UUID.
	 * This is called when a route request is finally resolved (found or failed)
	 * and no longer needs to be tracked.
	 * @param requestUuid The UUID of the route request to delete.
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM route_request_entry WHERE request_uuid = :requestUuid")
	int deleteByRequestUuid(String requestUuid);

	/**
	 * Retrieves a RouteRequestEntry by its unique request UUID.
	 * This is essential for finding the current state of a discovery request.
	 * @param requestUuid The UUID of the route request.
	 * @return The RouteRequestEntry if found, null otherwise.
	 */
	@Query("SELECT * FROM route_request_entry WHERE request_uuid = :requestUuid LIMIT 1")
	RouteRequestEntry getRequestByUuid(String requestUuid);
}
