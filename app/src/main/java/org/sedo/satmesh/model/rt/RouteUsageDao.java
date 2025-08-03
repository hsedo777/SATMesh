package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

/**
 * Data Access Object (DAO) for the RouteUsage entity.
 * Provides methods to manage the usage tracking of RouteEntry instances.
 */
@Dao
public interface RouteUsageDao {

	/**
	 * Inserts a new RouteUsage record or replaces an existing one if the usage_request_uuid conflicts.
	 * This is used to mark a route as being used by a specific application request.
	 *
	 * @param routeUsage The RouteUsage object to insert.
	 * @return The row ID of the inserted or replaced entry.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	Long insert(RouteUsage routeUsage);

	/**
	 * Updates an existing RouteUsage record.
	 * This is primarily used to update the 'last_used_timestamp' when a route is reused.
	 *
	 * @param routeUsage The RouteUsage object to update. It must have a valid 'usageRequestUuid'.
	 * @return The number of rows updated (should be 1 for a successful update).
	 */
	@Update
	int update(RouteUsage routeUsage);

	/**
	 * Deletes a specific RouteUsage record
	 *
	 * @param routeUsage The route usage to delete
	 * @return The number of rows deleted.
	 */
	@Delete
	int delete(RouteUsage routeUsage);

	/**
	 * Deletes all RouteUsage records associated with a specific RouteEntry.
	 * This helps in cleaning up stale usage records.
	 *
	 * @param routeEntryDiscoveryUuid The discovery UUID of the RouteEntry.
	 */
	@Query("DELETE FROM route_usage WHERE route_entry_discovery_uuid = :routeEntryDiscoveryUuid")
	void deleteUsagesForRouteEntry(String routeEntryDiscoveryUuid);
}
