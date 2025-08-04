package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/**
 * Data Access Object for the route_usage_backtracking table.
 * This interface provides methods for inserting, deleting, and finding RouteUsageBacktracking entries.
 *
 * @author hsedo777
 */
@Dao
public interface RouteUsageBacktrackingDao {

	/**
	 * Inserts a RouteUsageBacktracking entry into the database.
	 * If the entry already exists, it is replaced.
	 *
	 * @param backtracking The entry to insert.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insert(RouteUsageBacktracking backtracking);

	/**
	 * Deletes all RouteUsageBacktracking entries associated with a given route UUID.
	 *
	 * @param routeUuid The UUID of the route.
	 */
	@Query("DELETE FROM route_usage_backtracking WHERE usageUuid IN (SELECT usage_request_uuid FROM route_usage WHERE route_entry_discovery_uuid = :routeUuid)")
	void deleteByRouteUuid(String routeUuid);
}
