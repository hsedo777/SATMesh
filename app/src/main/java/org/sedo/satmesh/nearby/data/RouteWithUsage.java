package org.sedo.satmesh.nearby.data;

import androidx.annotation.NonNull;
import androidx.room.Embedded;

import org.sedo.satmesh.model.rt.RouteEntry;
import org.sedo.satmesh.model.rt.RouteUsage;
import org.sedo.satmesh.model.rt.RouteUsageBacktracking;

import java.util.Objects;

/**
 * Wrapper for {@link RouteEntry} and a date of usage (often the date of last usage).
 *
 * @author hsedo777
 */
public class RouteWithUsage {
	@Embedded
	@NonNull
	public RouteEntry routeEntry;
	@Embedded(prefix = "usage_")
	public RouteUsage routeUsage;
	@Embedded(prefix = "backtracking_")
	public RouteUsageBacktracking backtracking;

	public RouteWithUsage(@NonNull RouteEntry routeEntry, RouteUsage routeUsage, RouteUsageBacktracking backtracking) {
		this.routeEntry = routeEntry;
		this.routeUsage = routeUsage;
		this.backtracking = backtracking;
	}

	public boolean isWithoutUsage() {
		return routeUsage == null && backtracking == null;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		RouteWithUsage that = (RouteWithUsage) o;
		return Objects.equals(routeEntry, that.routeEntry) &&
				Objects.equals(routeUsage, that.routeUsage) &&
				Objects.equals(backtracking, that.backtracking);
	}

	@Override
	public int hashCode() {
		return Objects.hash(routeEntry, routeUsage, backtracking);
	}

	@NonNull
	@Override
	public String toString() {
		return "RouteWithUsage{" +
				"routeEntry=" + routeEntry +
				", routeUsage=" + routeUsage +
				", backtracking=" + backtracking +
				'}';
	}
}