package org.sedo.satmesh.model.rt;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface RouteUsageBacktrackingDao {

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insert(RouteUsageBacktracking routeUsageBacktracking);

	@Query("DELETE FROM route_usage_backtracking WHERE usageUuid IN (SELECT usage_request_uuid FROM route_usage WHERE route_entry_discovery_uuid = :routeUuid)")
	void deleteByRouteUuid(String routeUuid);
}
