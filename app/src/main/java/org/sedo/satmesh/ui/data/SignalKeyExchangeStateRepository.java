package org.sedo.satmesh.ui.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.SignalKeyExchangeState;
import org.sedo.satmesh.model.SignalKeyExchangeStateDao;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository to manage persistent key exchange states with remote nodes.
 * This class provides a clean API to interact with the database for SignalKeyExchangeState entities.
 */
public class SignalKeyExchangeStateRepository {

	private static final String TAG = "SignalKeyExchangeStateRepository";
	private final SignalKeyExchangeStateDao dao;
	private final ExecutorService executor;

	public SignalKeyExchangeStateRepository(@NonNull Context application) {
		AppDatabase db = AppDatabase.getDB(application);
		this.dao = db.signalKeyExchangeStateDao();
		this.executor = Executors.newSingleThreadExecutor(); // For database operations
	}

	/**
	 * Retrieves a SignalKeyExchangeState synchronously for a specific remote node.
	 * Use with caution, preferably on a background thread.
	 *
	 * @param remoteAddress The Signal Protocol address name of the remote node.
	 * @return The SignalKeyExchangeState, or null if not found.
	 */
	public SignalKeyExchangeState getByRemoteAddressSync(String remoteAddress) {
		return dao.getByRemoteAddress(remoteAddress);
	}

	/**
	 * Inserts or updates a SignalKeyExchangeState in the database.
	 * If a state for the remoteAddress doesn't exist, it's created.
	 * Otherwise, the existing one is updated.
	 *
	 * @param state The SignalKeyExchangeState to save.
	 */
	public void save(SignalKeyExchangeState state) {
		executor.execute(() -> {
			SignalKeyExchangeState existingState = dao.getByRemoteAddress(state.getRemoteAddress());
			if (existingState == null) {
				dao.insert(state);
				Log.d(TAG, "Inserted new SignalKeyExchangeState for: " + state.getRemoteAddress());
			} else {
				// Update the ID of the provided state to match the existing one
				state.setId(existingState.getId());
				dao.update(state);
				Log.d(TAG, "Updated existing SignalKeyExchangeState for: " + state.getRemoteAddress());
			}
		});
	}
}