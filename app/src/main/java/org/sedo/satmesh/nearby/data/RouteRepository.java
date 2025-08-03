package org.sedo.satmesh.nearby.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

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

import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class RouteRepository {

	private static final String TAG = "RouteRepository";

	private final Executor executor;
	private final RouteEntryDao routeEntryDao;
	private final RouteUsageDao routeUsageDao;
	private final RouteRequestEntryDao routeRequestEntryDao;
	private final BroadcastStatusEntryDao broadcastStatusEntryDao;
	private final RouteUsageBacktrackingDao backtrackingDao;

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

	public void hasBroadcastStatusInProgressState(String requestUuid, boolean pendingInProgress, ResultHandler<Boolean> resultHandler) {
		executor.execute(() -> resultHandler.onTerminated(broadcastStatusEntryDao.hasResponseInProgressState(requestUuid, pendingInProgress)));
	}

	public BroadcastStatusEntry findBroadcastStatusSync(String requestUuid, Long neighborNodeLocalId) {
		return broadcastStatusEntryDao.findBroadcastStatusSync(requestUuid, neighborNodeLocalId);
	}

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

	public RouteEntry.RouteWithUsage findMostRecentRouteByDestinationSync(long destinationNodeLocalId) {
		return routeEntryDao.findMostRecentRouteByDestinationSync(destinationNodeLocalId);
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

	public void dropRouteAndItsUsages(@NonNull RouteEntry routeEntry) {
		executor.execute(() -> {
			backtrackingDao.deleteByRouteUuid(routeEntry.getDiscoveryUuid());
			routeUsageDao.deleteUsagesForRouteEntry(routeEntry.getDiscoveryUuid());
			routeEntryDao.delete(routeEntry);
		});
	}
}
