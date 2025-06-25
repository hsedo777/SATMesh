package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
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
	 * Deletes a specific RouteUsage record by its usage request UUID.
	 * This is used when an application request that was using a route is completed or cancelled.
	 *
	 * @param usageRequestUuid The UUID of the application request using the route.
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM route_usage WHERE usage_request_uuid = :usageRequestUuid")
	int deleteByUsageRequestUuid(String usageRequestUuid);

	/**
	 * Retrieves a RouteUsage record by its unique usage request UUID.
	 *
	 * @param usageRequestUuid The UUID of the application request using the route.
	 * @return The RouteUsage record if found, null otherwise.
	 */
	@Query("SELECT * FROM route_usage WHERE usage_request_uuid = :usageRequestUuid")
	RouteUsage getRouteUsageByRequestUuid(String usageRequestUuid);

	/**
	 * Retrieves the most recently used (opened) route usage entry for a given destination node.
	 * This method looks for a {@link RouteEntry} that matches the specified destination,
	 * ensures it's an "opened" route (meaning it has a valid next hop), and then
	 * returns the {@link RouteUsage} record associated with that route that was
	 * most recently updated.
	 *
	 * @param destinationNodeLocalId The local ID of the destination node for which to find the route usage.
	 * @return The {@link RouteUsage} object representing the most recent use of an
	 * active route to the specified destination, or {@code null} if no such
	 * route usage entry is found.
	 */
	@Query("SELECT RU.* " +
			"FROM route_entry AS RE " +
			"INNER JOIN route_usage AS RU ON RE.discovery_uuid = RU.route_entry_discovery_uuid " +
			"WHERE RE.destination_node_local_id = :destinationNodeLocalId " +
			"AND RE.next_hop_local_id IS NOT NULL " + // Corresponds to isOpened()
			"ORDER BY RU.last_used_timestamp DESC " +
			"LIMIT 1")
	RouteUsage getMostRecentRouteUsageForDestinationSync(long destinationNodeLocalId);

	/**
	 * Deletes all RouteUsage records associated with a specific RouteEntry that have not been
	 * used since a given timestamp. This helps in cleaning up stale usage records.
	 *
	 * @param routeEntryDiscoveryUuid The discovery UUID of the RouteEntry.
	 * @param thresholdTimestamp      The timestamp (in milliseconds) before which usage is considered old.
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM route_usage WHERE route_entry_discovery_uuid = :routeEntryDiscoveryUuid AND last_used_timestamp < :thresholdTimestamp")
	int deleteStaleUsagesForRouteEntry(String routeEntryDiscoveryUuid, Long thresholdTimestamp);
}
