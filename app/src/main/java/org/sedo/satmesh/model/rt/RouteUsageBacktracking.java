package org.sedo.satmesh.model.rt;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "route_usage_backtracking")
public record RouteUsageBacktracking(
		@PrimaryKey @NonNull @ColumnInfo(name = "usage_uuid") String usageUuid,
		@NonNull @ColumnInfo(name = "destination_node_local_id") Long destinationNodeLocalId) {
}
