package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import org.sedo.satmesh.model.rt.RouteEntry.RouteWithUsageTimestamp;

import java.util.List;

/**
 * Data Access Object (DAO) for the RouteEntry entity.
 * Provides methods to interact with the 'route_entries' table in the local database.
 */
@Dao
public interface RouteEntryDao {

	/**
	 * Inserts a new RouteEntry into the database.
	 * If a conflict occurs (e.g., a RouteEntry with the same discovery_uuid already exists,
	 * due to the unique index), the existing entry will be replaced.
	 *
	 * @param routeEntry The RouteEntry to insert.
	 * @return The row ID of the newly inserted or replaced entry.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	Long insert(RouteEntry routeEntry);

	/**
	 * Updates an existing RouteEntry in the database.
	 *
	 * @param routeEntry The RouteEntry to update. It must have a valid 'id'.
	 * @return The number of rows updated (should be 1 for a successful update).
	 */
	@Update
	int update(RouteEntry routeEntry);

	/**
	 * Deletes a specific RouteEntry from the database.
	 *
	 * @param routeEntry The RouteEntry to delete.
	 * @return The number of rows deleted (should be 1 for a successful deletion).
	 */
	@Delete
	int delete(RouteEntry routeEntry);

	/**
	 * Retrieves a RouteEntry by its unique discovery UUID.
	 * This is useful when processing a RouteResponseMessage to find the corresponding discovered route.
	 *
	 * @param discoveryUuid The UUID of the discovery request that created the route.
	 * @return The RouteEntry if found, null otherwise.
	 */
	@Query("SELECT * FROM route_entry WHERE discovery_uuid = :discoveryUuid")
	RouteEntry getRouteByDiscoveryUuidSync(String discoveryUuid);

	/**
	 * Retrieves all RouteEntries that lead to a specific destination node.
	 * This is used when a node needs to find all known paths to a target.
	 *
	 * @param destinationNodeLocalId The local ID of the destination node.
	 * @return A list of RouteEntry objects to the specified destination.
	 */
	@Query("SELECT * FROM route_entry WHERE destination_node_local_id = :destinationNodeLocalId")
	List<RouteEntry> getRoutesByDestinationLocalIdSync(long destinationNodeLocalId);

	/**
	 * Retrieves the most recently used "opened" route entry for a given destination node,
	 * along with its last usage timestamp.
	 * An "opened" route is defined as one where the next hop is established
	 * (next_hop_local_id is not null). The "most recent" is determined by
	 * the last_used_timestamp in the RouteUsage table.
	 *
	 * @param destinationNodeLocalId The local ID of the destination node.
	 * @return A RouteWithUsageTimestamp object containing the most recently used opened RouteEntry and its last usage timestamp,
	 * or null if no such route exists.
	 */
	@Query("SELECT RE.*, RU.last_used_timestamp " +
			"FROM route_entry AS RE " +
			"INNER JOIN route_usage AS RU ON RE.discovery_uuid = RU.route_entry_discovery_uuid " +
			"WHERE RE.destination_node_local_id = :destinationNodeLocalId " +
			"AND RE.next_hop_local_id IS NOT NULL " + // Corresponds to isOpened()
			"ORDER BY RU.last_used_timestamp DESC " +
			"LIMIT 1")
	RouteWithUsageTimestamp getMostRecentOpenedRouteByDestinationSync(long destinationNodeLocalId);
}
