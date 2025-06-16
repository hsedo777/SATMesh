package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object (DAO) for the RouteUsage entity.
 * Provides methods to manage the usage tracking of RouteEntry instances.
 */
@Dao
public interface RouteUsageDao {

	/**
	 * Inserts a new RouteUsage record or replaces an existing one if the usage_request_uuid conflicts.
	 * This is used to mark a route as being used by a specific application request.
	 * @param routeUsage The RouteUsage object to insert.
	 * @return The row ID of the inserted or replaced entry.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	Long insert(RouteUsage routeUsage);

	/**
	 * Updates an existing RouteUsage record.
	 * This is primarily used to update the 'last_used_timestamp' when a route is reused.
	 * @param routeUsage The RouteUsage object to update. It must have a valid 'usageRequestUuid'.
	 * @return The number of rows updated (should be 1 for a successful update).
	 */
	@Update
	int update(RouteUsage routeUsage);

	/**
	 * Deletes a specific RouteUsage record by its usage request UUID.
	 * This is used when an application request that was using a route is completed or cancelled.
	 * @param usageRequestUuid The UUID of the application request using the route.
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM route_usage WHERE usage_request_uuid = :usageRequestUuid")
	int deleteByUsageRequestUuid(String usageRequestUuid);

	/**
	 * Retrieves a RouteUsage record by its unique usage request UUID.
	 * @param usageRequestUuid The UUID of the application request using the route.
	 * @return The RouteUsage record if found, null otherwise.
	 */
	@Query("SELECT * FROM route_usage WHERE usage_request_uuid = :usageRequestUuid LIMIT 1")
	RouteUsage getRouteUsageByRequestUuid(String usageRequestUuid);

	/**
	 * Retrieves all RouteUsage records associated with a specific RouteEntry (identified by its discovery_uuid).
	 * This can be used to check how many different application requests are currently using a particular route.
	 * @param routeEntryDiscoveryUuid The discovery UUID of the RouteEntry.
	 * @return A list of RouteUsage records for the specified route.
	 */
	@Query("SELECT * FROM route_usage WHERE route_entry_discovery_uuid = :routeEntryDiscoveryUuid")
	List<RouteUsage> getRouteUsagesByRouteEntryDiscoveryUuid(String routeEntryDiscoveryUuid);

	/**
	 * Finds the maximum (most recent) 'last_used_timestamp' for a given RouteEntry.
	 * This is crucial for determining the overall inactivity of a RouteEntry based on its usages.
	 * @param routeEntryDiscoveryUuid The discovery UUID of the RouteEntry.
	 * @return The maximum timestamp, or null if no usage records exist for the route.
	 */
	@Query("SELECT MAX(last_used_timestamp) FROM route_usage WHERE route_entry_discovery_uuid = :routeEntryDiscoveryUuid")
	Long getMaxLastUsedTimestampForRoute(String routeEntryDiscoveryUuid);

	/**
	 * Deletes all RouteUsage records associated with a specific RouteEntry that have not been
	 * used since a given timestamp. This helps in cleaning up stale usage records.
	 * @param routeEntryDiscoveryUuid The discovery UUID of the RouteEntry.
	 * @param thresholdTimestamp The timestamp (in milliseconds) before which usage is considered old.
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM route_usage WHERE route_entry_discovery_uuid = :routeEntryDiscoveryUuid AND last_used_timestamp < :thresholdTimestamp")
	int deleteStaleUsagesForRouteEntry(String routeEntryDiscoveryUuid, Long thresholdTimestamp);
}
