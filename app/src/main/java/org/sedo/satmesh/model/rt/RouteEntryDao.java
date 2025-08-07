package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import org.sedo.satmesh.nearby.data.RouteWithUsage;

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
	 * Retrieves all RouteEntry from the database.
	 *
	 * @param discoveryUuid The discovery UUID of the RouteEntry.
	 * @return A RouteEntry object.
	 */
	@Query("SELECT * FROM route_entry WHERE discovery_uuid = :discoveryUuid")
	RouteEntry findByDiscoveryUuid(String discoveryUuid);

	/**
	 * Retrieves the most recently used "opened" route entry for a given destination node,
	 * along with its last usage timestamp.
	 *
	 * @param destinationNodeLocalId The local ID of the destination node.
	 * @return A RouteWithUsage object containing the most recently used opened RouteEntry,
	 * its RouteUsage and if exists its RouteUsageBacktracking, or null if no such route exists.
	 */
	@Query("SELECT " +
			// Select fields from RouteEntry
			"RE.id AS id, " +
			"RE.discovery_uuid AS discovery_uuid, " +
			"RE.destination_node_local_id AS destination_node_local_id, " +
			"RE.next_hop_local_id AS next_hop_local_id, " +
			"RE.previous_hop_local_id AS previous_hop_local_id, " +
			"RE.hop_count AS hop_count, " +
			"RE.last_use_timestamp AS last_use_timestamp, " +
			// Select fields from RouteUsage
			"RU.usage_request_uuid AS usage_usage_request_uuid, " +
			"RU.route_entry_discovery_uuid AS usage_route_entry_discovery_uuid, " +
			"RU.previous_hop_local_id AS usage_previous_hop_local_id, " +
			// Select fields from RouteUsageBacktracking
			"RB.destination_node_local_id AS backtracking_destination_node_local_id, " +
			"RB.usage_uuid AS backtracking_usage_uuid " +
			"FROM route_entry AS RE " +
			"LEFT OUTER JOIN route_usage AS RU ON RE.discovery_uuid = RU.route_entry_discovery_uuid " +
			"LEFT OUTER JOIN route_usage_backtracking AS RB ON RU.usage_request_uuid = RB.usage_uuid " +
			"WHERE (RE.destination_node_local_id = :destinationNodeLocalId OR RB.destination_node_local_id = :destinationNodeLocalId) " +
			"AND RE.last_use_timestamp IS NOT NULL " +
			"ORDER BY RE.last_use_timestamp DESC " +
			"LIMIT 1")
	RouteWithUsage findMostRecentRouteByDestination(long destinationNodeLocalId);
}
