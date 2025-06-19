package org.sedo.satmesh.model.rt;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import static androidx.room.ForeignKey.CASCADE; // For onDelete behavior

import java.util.Objects;

/**
 * Represents a record of a specific route entry being used by a particular application request or session.
 * This entity's primary purpose is to track the last time a {@link RouteEntry} was actively used
 * in the context of a given request UUID, enabling the 12-hour inactivity logic.
 * Each entry in this table signifies that a specific application request (identified by 'usageRequestUuid')
 * is currently utilizing a particular discovered route (identified by 'routeEntryDiscoveryUuid').
 * @see RouteEntry
 * @author hovozounkou
 */
@Entity(tableName = "route_usage",
		foreignKeys = @ForeignKey(
				entity = RouteEntry.class,
				parentColumns = "discovery_uuid", // Refers to the unique primary key/column in RouteEntry
				childColumns = "route_entry_discovery_uuid",
				onDelete = CASCADE // If the referenced RouteEntry is deleted, associated RouteUsages are also deleted.
		),
		indices = {
				// An index on route_entry_discovery_uuid to optimize lookups
				// for all usage records associated with a specific route entry.
				@Index(value = {"route_entry_discovery_uuid"})
		})
public class RouteUsage {

	/*
	 * The unique identifier of the application-level request or session that is making use of a route.
	 * This attribute serves as the primary key for the 'route_usage' entity.
	 *  - When a route is initially discovered for a new application request,
	 *   this will be the UUID of that very first request.
	 * - When an existing RouteEntry is reused for a subsequent new application demand
	 *   (i.e., a different application-level UUID for a new communication session),
	 *   a new 'RouteUsage' record will be created. The 'usageRequestUuid' for this new record
	 *   will be the UUID of this newly received demand. This ensures that each distinct demand
	 *   maintains its own usage tracking for a given route.
	 */
	@NonNull
	@PrimaryKey
	@ColumnInfo(name = "usage_request_uuid")
	private String usageRequestUuid;

	/*
	 * The UUID of the specific RouteEntry (specifically, its 'discovery_uuid' attribute)
	 * that is currently being utilized by the application request identified by 'usageRequestUuid'.
	 * This acts as a foreign key, linking this usage record to the actual definition of the route
	 * in the 'route_entry' table.
	 */
	@ColumnInfo(name = "route_entry_discovery_uuid")
	private String routeEntryDiscoveryUuid;

	// The timestamp (e.g., System.currentTimeMillis()) when this RouteEntry was last actively
	// used specifically by the application request or session identified by 'usageRequestUuid'.
	// This timestamp is critical for implementing the 12-hour inactivity expiration logic.
	@ColumnInfo(name = "last_used_timestamp")
	private Long lastUsedTimestamp;

	/**
	 * Default constructor. Required by Room for entity instantiation.
	 */
	public RouteUsage(@NonNull String usageRequestUuid) {
		this.usageRequestUuid = usageRequestUuid;
	}

	// --- Getters ---

	@NonNull
	public String getUsageRequestUuid() {
		return usageRequestUuid;
	}

	public String getRouteEntryDiscoveryUuid() {
		return routeEntryDiscoveryUuid;
	}

	public Long getLastUsedTimestamp() {
		return lastUsedTimestamp;
	}

	// --- Setters ---

	public void setUsageRequestUuid(@NonNull String usageRequestUuid) {
		this.usageRequestUuid = usageRequestUuid;
	}

	public void setRouteEntryDiscoveryUuid(String routeEntryDiscoveryUuid) {
		this.routeEntryDiscoveryUuid = routeEntryDiscoveryUuid;
	}

	public void setLastUsedTimestamp(Long lastUsedTimestamp) {
		this.lastUsedTimestamp = lastUsedTimestamp;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		RouteUsage that = (RouteUsage) o;
		return Objects.equals(usageRequestUuid, that.usageRequestUuid) && Objects.equals(routeEntryDiscoveryUuid, that.routeEntryDiscoveryUuid) && Objects.equals(lastUsedTimestamp, that.lastUsedTimestamp);
	}

	@Override
	public int hashCode() {
		return Objects.hash(usageRequestUuid, routeEntryDiscoveryUuid, lastUsedTimestamp);
	}

	@NonNull
	@Override
	public String toString() {
		return "RouteUsage{" +
				"usageRequestUuid='" + usageRequestUuid + '\'' +
				", routeEntryDiscoveryUuid='" + routeEntryDiscoveryUuid + '\'' +
				", lastUsedTimestamp=" + lastUsedTimestamp +
				'}';
	}
}
