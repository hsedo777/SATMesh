package org.sedo.satmesh.nearby.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.rt.BroadcastStatusEntry;
import org.sedo.satmesh.model.rt.BroadcastStatusEntryDao;
import org.sedo.satmesh.model.rt.RouteEntry;
import org.sedo.satmesh.model.rt.RouteEntryDao;
import org.sedo.satmesh.model.rt.RouteRequestEntry;
import org.sedo.satmesh.model.rt.RouteRequestEntryDao;
import org.sedo.satmesh.model.rt.RouteUsage;
import org.sedo.satmesh.model.rt.RouteUsageBacktracking;
import org.sedo.satmesh.model.rt.RouteUsageBacktrackingDao;
import org.sedo.satmesh.model.rt.RouteUsageDao;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Repository for managing route-related data, including route entries, route usage,
 * route requests, broadcast statuses, and route usage backtracking.
 * It interacts with the underlying DAOs to perform database operations.
 *
 * @author hsedo777
 */
public class RouteRepository {

	private static final String TAG = "RouteRepository";

	private final Executor executor;
	private final RouteEntryDao routeEntryDao;
	private final RouteUsageDao routeUsageDao;
	private final RouteRequestEntryDao routeRequestEntryDao;
	private final BroadcastStatusEntryDao broadcastStatusEntryDao;
	private final RouteUsageBacktrackingDao backtrackingDao;

	/**
	 * Constructs a new RouteRepository.
	 *
	 * @param context The application context.
	 */
	public RouteRepository(@NonNull Context context) {
		AppDatabase appDatabase = AppDatabase.getDB(context);
		this.routeEntryDao = appDatabase.routeEntryDao();
		this.routeUsageDao = appDatabase.routeUsageDao();
		this.routeRequestEntryDao = appDatabase.routeRequestEntryDao();
		this.broadcastStatusEntryDao = appDatabase.broadcastStatusEntryDao();
		this.backtrackingDao = appDatabase.routeUsageBacktrackingDao();
		executor = appDatabase.getQueryExecutor();
	}

	/**
	 * Insert a BroadcastStatusEntry into the database.
	 *
	 * @param broadcastStatusEntry The BroadcastStatusEntry to be inserted.
	 * @param callback             The callback to be executed upon completion.
	 */
	public void insertBroadcastStatus(@NonNull BroadcastStatusEntry broadcastStatusEntry, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				broadcastStatusEntryDao.insert(broadcastStatusEntry);
				callback.accept(true);
			} catch (Exception e) {
				Log.e(TAG, "Error inserting BroadcastStatusEntry: ", e);
				callback.accept(false);
			}
		});
	}

	/**
	 * Updates an existing BroadcastStatusEntry in the database.
	 *
	 * @param broadcastStatus The BroadcastStatusEntry to be updated.
	 * @param callback        The callback to be executed upon completion, indicating success or failure.
	 */
	public void updateBroadcastStatus(@NonNull BroadcastStatusEntry broadcastStatus, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				broadcastStatusEntryDao.update(broadcastStatus);
				callback.accept(true);
			} catch (Exception e) {
				Log.e(TAG, "Error updating BroadcastStatusEntry: ", e);
				callback.accept(false);
			}
		});
	}

	/**
	 * Deletes a BroadcastStatusEntry from the database.
	 *
	 * @param broadcastStatus The BroadcastStatusEntry to be deleted.
	 * @param callback        The callback to be executed upon completion, indicating success or failure.
	 */
	public void deleteBroadcastStatus(@NonNull BroadcastStatusEntry broadcastStatus, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				broadcastStatusEntryDao.delete(broadcastStatus);
				callback.accept(true);
			} catch (Exception e) {
				Log.e(TAG, "Error deleting BroadcastStatusEntry: ", e);
				callback.accept(false);
			}
		});
	}

	/**
	 * Checks if there is any BroadcastStatusEntry in an in-progress state for a given request UUID.
	 *
	 * @param requestUuid       The UUID of the request.
	 * @param pendingInProgress Whether to check for pending in-progress state.
	 * @param resultHandler     The handler to receive the boolean result.
	 */
	public void hasBroadcastStatusInProgressState(String requestUuid, boolean pendingInProgress, ResultHandler<Boolean> resultHandler) {
		executor.execute(() -> resultHandler.onTerminated(broadcastStatusEntryDao.hasResponseInProgressState(requestUuid, pendingInProgress)));
	}

	/**
	 * Finds a BroadcastStatusEntry synchronously by request UUID and neighbor node local ID.
	 *
	 * @param requestUuid         The UUID of the request.
	 * @param neighborNodeLocalId The local ID of the neighbor node.
	 * @return The found BroadcastStatusEntry, or null if not found.
	 */
	public BroadcastStatusEntry findBroadcastStatusSync(String requestUuid, Long neighborNodeLocalId) {
		return broadcastStatusEntryDao.findBroadcastStatus(requestUuid, neighborNodeLocalId);
	}

	/**
	 * Deletes all BroadcastStatusEntry records associated with the given request UUID.
	 *
	 * @param requestUuid The UUID of the request.
	 */
	public void dropBroadcastStatusesByRequestUuid(@NonNull String requestUuid) {
		executor.execute(() -> broadcastStatusEntryDao.deleteAllByRequestUuid(requestUuid));
	}

	/**
	 * Deletes all BroadcastStatusEntry on route with the specified UUID and then fetch the number
	 * of deleted rows by the callback.
	 *
	 * @param requestUuid          The route (request) UUID
	 * @param deletedCountCallback The callback to be executed upon completion.
	 */
	public void dropBroadcastStatusesByRequestUuid(@NonNull String requestUuid, @NonNull ResultHandler<Integer> deletedCountCallback) {
		executor.execute(() -> deletedCountCallback.onTerminated(broadcastStatusEntryDao.deleteAllByRequestUuid(requestUuid)));
	}

	/**
	 * Insert a RouteRequestEntry into the database.
	 *
	 * @param routeRequestEntry The RouteRequestEntry to be inserted.
	 * @param callback          The callback to be executed upon completion.
	 */
	public void insertRouteRequest(@NonNull RouteRequestEntry routeRequestEntry, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				routeRequestEntryDao.insert(routeRequestEntry);
				callback.accept(true);
			} catch (Exception e) {
				Log.e(TAG, "Error inserting RouteRequestEntry: ", e);
				callback.accept(false);
			}
		});
	}

	/**
	 * Finds the most recent route to a destination, along with its usage, synchronously.
	 *
	 * @param destinationNodeLocalId The local ID of the destination node.
	 * @return The most recent RouteEntry.RouteWithUsage, or null if not found.
	 */
	public RouteWithUsage findMostRecentRouteByDestinationSync(long destinationNodeLocalId) {
		return routeEntryDao.findMostRecentRouteByDestination(destinationNodeLocalId);
	}

	/**
	 * Delete a RouteRequestEntry by its requestUuid. This method is executed on a background thread.
	 *
	 * @param requestUuid The UUID of the RouteRequestEntry to be deleted.
	 */
	public void deleteRouteRequestByRequestUuid(@NonNull String requestUuid) {
		executor.execute(() -> routeRequestEntryDao.deleteByRequestUuid(requestUuid));
	}

	/**
	 * Delete {@code RouteRequestEntry}s by requestUuid. This method is executed on a background thread.
	 * And then fetch the number of deleted entries using the callback.
	 *
	 * @param requestUuid          The route request UUID
	 * @param deletedCountCallback Called with the {@code onSuccess} method to give access to the number of deleted row.
	 */
	public void deleteRouteRequestByRequestUuid(@NonNull String requestUuid, @NonNull ResultHandler<Integer> deletedCountCallback) {
		executor.execute(() -> deletedCountCallback.onTerminated(routeRequestEntryDao.deleteByRequestUuid(requestUuid)));
	}

	/**
	 * Insert a RouteEntry into the database. This method is executed on a background thread.
	 *
	 * @param routeEntry The RouteEntry to be inserted.
	 * @param callback   The callback to be executed upon completion.
	 */
	public void insertRouteEntry(@NonNull RouteEntry routeEntry, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				routeEntry.setId(routeEntryDao.insert(routeEntry));
				callback.accept(true);
			} catch (Exception e) {
				Log.e(TAG, "Error inserting RouteEntry: ", e);
				callback.accept(false);
			}
		});
	}

	/**
	 * Update a RouteEntry in the database. This method is executed on a background thread.
	 *
	 * @param routeEntry The RouteEntry to be updated.
	 * @param callback   The callback to be executed upon completion.
	 */
	public void updateRouteEntry(@NonNull RouteEntry routeEntry, @Nullable Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				routeEntryDao.update(routeEntry);
				if (callback != null) {
					callback.accept(true);
				}
			} catch (Exception e) {
				Log.e(TAG, "Error inserting RouteEntry: ", e);
				if (callback != null) {
					callback.accept(false);
				}
			}
		});
	}

	/**
	 * Finds a RouteEntry by its discovery UUID synchronously.
	 *
	 * @param discoveryUuid The discovery UUID of the route.
	 * @return The found RouteEntry, or null if not found.
	 */
	public RouteEntry findRouteByDiscoveryUuidSync(@NonNull String discoveryUuid) {
		return routeEntryDao.findByDiscoveryUuid(discoveryUuid);
	}

	/**
	 * Finds a RouteUsage by its usage request UUID synchronously.
	 *
	 * @param usageRequestUuid The usage request UUID.
	 * @return The found RouteUsage, or null if not found.
	 */
	public RouteUsage findRouteUsageByUsageUuidSync(@NonNull String usageRequestUuid) {
		return routeUsageDao.findByUsageRequestUuid(usageRequestUuid);
	}

	/**
	 * Insert a RouteUsage into the database. This method is executed on a background thread.
	 *
	 * @param routeUsage The RouteUsage to be inserted.
	 * @param callback   The callback to be executed upon completion.
	 */
	public void insertRouteUsage(@NonNull RouteUsage routeUsage, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				routeUsageDao.insert(routeUsage);
				callback.accept(true);
			} catch (Exception e) {
				Log.e(TAG, "Error inserting RouteUsage: ", e);
				callback.accept(false);
			}
		});
	}

	/**
	 * Finds all RouteUsage entries associated with a specific route UUID synchronously.
	 *
	 * @param routeUuid The UUID of the route.
	 * @return A list of RouteUsage entries.
	 */
	public List<RouteUsage> findRouteUsagesByRouteUuidSync(String routeUuid) {
		return routeUsageDao.findByRouteUuid(routeUuid);
	}

	/**
	 * Insert a RouteUsageBacktracking into the database.
	 *
	 * @param routeUsageBacktracking The RouteUsageBacktracking to be inserted.
	 * @param callback               The callback to be executed upon completion.
	 */
	public void insertRouteUsageBacktracking(@NonNull RouteUsageBacktracking routeUsageBacktracking, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				backtrackingDao.insert(routeUsageBacktracking);
				callback.accept(true);
			} catch (Exception e) {
				Log.e(TAG, "Error inserting RouteUsageBacktracking: ", e);
				callback.accept(false);
			}
		});
	}

	/**
	 * Gets RouteRequestEntry by route UUID.
	 *
	 * @param routeUuid The route UUID
	 * @return the matched route request entry, {@code null} if there is no matching.
	 */
	public RouteRequestEntry findRouteRequestByUuidSync(@NonNull String routeUuid) {
		return routeRequestEntryDao.getRequestByUuid(routeUuid);
	}

	/**
	 * Gets RouteRequestEntry by route UUID, asynchronously.
	 *
	 * @param routeUuid The route UUID
	 * @param callback  The callback to handle the result or failure.
	 */
	public void findRouteRequestByUuidAsync(@NonNull String routeUuid, @NonNull SelectionCallback<RouteRequestEntry> callback) {
		executor.execute(() -> {
			try {
				RouteRequestEntry routeRequestEntry = routeRequestEntryDao.getRequestByUuid(routeUuid);
				if (routeRequestEntry == null) {
					callback.onFailure(null);
				} else {
					callback.onSuccess(routeRequestEntry);
				}
			} catch (Exception e) {
				Log.e(TAG, "Error getting RouteRequestEntry: ", e);
				callback.onFailure(e);
			}
		});
	}

	/**
	 * Deletes a route entry and all its associated usages and backtracking entries.
	 * This operation is performed on a background thread.
	 *
	 * @param routeEntry The RouteEntry to be deleted.
	 */
	public void dropRouteAndItsUsages(@NonNull RouteEntry routeEntry) {
		executor.execute(() -> {
			backtrackingDao.deleteByRouteUuid(routeEntry.getDiscoveryUuid());
			routeUsageDao.deleteUsagesForRouteEntry(routeEntry.getDiscoveryUuid());
			routeEntryDao.delete(routeEntry);
		});
	}

	/**
	 * Deletes all route usage and backtracking entries associated to the specified route UUID.
	 * This operation is performed on a background thread.
	 *
	 * @param routeUuid The route UUID of usages to be deleted.
	 */
	public void dropRouteUsages(@NonNull String routeUuid) {
		executor.execute(() -> {
			backtrackingDao.deleteByRouteUuid(routeUuid);
			routeUsageDao.deleteUsagesForRouteEntry(routeUuid);
		});
	}
}

