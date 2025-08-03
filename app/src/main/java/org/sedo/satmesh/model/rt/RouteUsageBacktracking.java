package org.sedo.satmesh.model.rt;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "route_usage_backtracking")
public record RouteUsageBacktracking(@PrimaryKey @NonNull String usageUuid,
									 @NonNull Long destinationNodeLocalId) {
}
