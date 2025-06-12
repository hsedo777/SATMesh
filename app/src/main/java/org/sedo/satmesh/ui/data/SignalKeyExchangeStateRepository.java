package org.sedo.satmesh.ui.data;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.SignalKeyExchangeState;
import org.sedo.satmesh.model.SignalKeyExchangeStateDao;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository to manage persistent key exchange states with remote nodes.
 * This class provides a clean API to interact with the database for SignalKeyExchangeState entities.
 */
public class SignalKeyExchangeStateRepository {

	private static final String TAG = "SignalKeyExchangeStateRepository";
	private final SignalKeyExchangeStateDao signalKeyExchangeStateDao;
	private final ExecutorService executor;

	public SignalKeyExchangeStateRepository(@NonNull Context application) {
		// Assuming your main database class is AppDatabase
		AppDatabase db = AppDatabase.getDB(application);
		this.signalKeyExchangeStateDao = db.signalKeyExchangeStateDao();
		this.executor = Executors.newSingleThreadExecutor(); // For database operations
	}

	/**
	 * Retrieves a LiveData object for the SignalKeyExchangeState of a specific remote node.
	 *
	 * @param remoteAddress The Signal Protocol address name of the remote node.
	 * @return LiveData containing the SignalKeyExchangeState, or null if not found.
	 */
	public LiveData<SignalKeyExchangeState> getByRemoteAddressLiveData(String remoteAddress) {
		return signalKeyExchangeStateDao.getByRemoteAddressLiveData(remoteAddress);
	}

	/**
	 * Retrieves a SignalKeyExchangeState synchronously for a specific remote node.
	 * Use with caution, preferably on a background thread.
	 *
	 * @param remoteAddress The Signal Protocol address name of the remote node.
	 * @return The SignalKeyExchangeState, or null if not found.
	 */
	public SignalKeyExchangeState getByRemoteAddressSync(String remoteAddress) {
		return signalKeyExchangeStateDao.getByRemoteAddress(remoteAddress);
	}



	/**
	 * Deletes the SignalKeyExchangeState for a specific remote node.
	 *
	 * @param remoteAddress The Signal Protocol address name of the remote node.
	 */
	public void deleteByRemoteAddress(String remoteAddress) {
		executor.execute(() -> signalKeyExchangeStateDao.deleteByRemoteAddress(remoteAddress));
	}

	// You might also want a method to get all states if needed:
	public LiveData<List<SignalKeyExchangeState>> getAllStates() {
		return signalKeyExchangeStateDao.getAllStates();
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
			SignalKeyExchangeState existingState = signalKeyExchangeStateDao.getByRemoteAddress(state.getRemoteAddress());
			if (existingState == null) {
				signalKeyExchangeStateDao.insert(state);
				Log.d(TAG, "Inserted new SignalKeyExchangeState for: " + state.getRemoteAddress());
			} else {
				// Update the ID of the provided state to match the existing one
				state.setId(existingState.getId());
				signalKeyExchangeStateDao.update(state);
				Log.d(TAG, "Updated existing SignalKeyExchangeState for: " + state.getRemoteAddress());
			}
		});
	}
}