package org.sedo.satmesh.ui.data;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Repository to manage transient UI states of nodes, such as discovery,
 * connection initiation, or temporary disconnection. These states are not
 * persisted in the database but are crucial for real-time UI feedback.
 * This class is implemented as a Singleton to ensure a single, consistent
 * source of transient states across the application's lifecycle.
 */
public class NodeStateRepository {

	private static final String TAG = "NodeStateRepository";
	private static volatile NodeStateRepository INSTANCE; // Singleton instance
	/** Number of milliseconds for temporary states like ON_DISCONNECTED. */
	private static final long REMOVAL_DELAY_MS = 5000; // 5 seconds

	// MutableLiveData to hold the map of transient node states.
	// Key: Node's addressName, Value: NodeState
	private final MutableLiveData<Map<String, NodeState>> transientNodeStatesLiveData = new MutableLiveData<>();

	// Executor for handling state updates and timed removals to avoid blocking the main thread.
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	/**
	 * Private constructor to enforce Singleton pattern.
	 */
	private NodeStateRepository() {
		// Initialize with an empty ConcurrentHashMap to be thread-safe for internal map operations.
		transientNodeStatesLiveData.setValue(new ConcurrentHashMap<>());
	}

	/**
	 * Provides the singleton instance of the NodeStateRepository.
	 *
	 * @return The singleton instance of NodeStateRepository.
	 */
	public static NodeStateRepository getInstance() {
		if (INSTANCE == null) {
			synchronized (NodeStateRepository.class) { // Double-checked locking for thread safety
				if (INSTANCE == null) {
					INSTANCE = new NodeStateRepository();
				}
			}
		}
		return INSTANCE;
	}

	/**
	 * Returns a LiveData object that holds the current map of transient node states.
	 * Observers can subscribe to this LiveData to receive updates whenever a
	 * transient state changes.
	 *
	 * @return LiveData containing a map where keys are node address names and values are their NodeState.
	 */
	public LiveData<Map<String, NodeState>> getTransientNodeStates() {
		return transientNodeStatesLiveData;
	}

	/**
	 * Updates the transient UI state for a given node.
	 * This method should be called by components (e.g., SATMeshCommunicationService)
	 * that receive real-time updates on node connection states not directly
	 * persisted in the database.
	 *
	 * @param addressName The Signal Protocol address name of the node.
	 * @param newState    The new transient state for the node.
	 */
	public void updateTransientNodeState(String addressName, NodeState newState) {
		if (addressName == null || newState == null) {
			Log.w(TAG, "Attempted to update transient state with null addressName or newState.");
			return;
		}

		executor.execute(() -> {
			Map<String, NodeState> currentStates = transientNodeStatesLiveData.getValue();
			if (currentStates == null) {
				// This should not happen with initial ConcurrentHashMap, but as a safeguard.
				currentStates = new ConcurrentHashMap<>();
				transientNodeStatesLiveData.postValue(currentStates);
			}

			Log.d(TAG, "Updating transient state for " + addressName + ": " + newState);

			if (newState == NodeState.ON_DISCONNECTED) {
				// For disconnected state, add it and schedule a removal after a delay.
				// This allows the UI to show "disconnected" briefly before potentially removing it.
				currentStates.put(addressName, newState);
				transientNodeStatesLiveData.postValue(new ConcurrentHashMap<>(currentStates)); // Post a copy to trigger observers

				executor.schedule(() -> {
					// Only remove if still in ON_DISCONNECTED state (i.e., not reconnected)
					Map<String, NodeState> statesAfterDelay = transientNodeStatesLiveData.getValue();
					if (statesAfterDelay != null && statesAfterDelay.get(addressName) == NodeState.ON_DISCONNECTED) {
						Log.d(TAG, "Removing " + addressName + " from transient states after delay.");
						statesAfterDelay.remove(addressName);
						transientNodeStatesLiveData.postValue(new ConcurrentHashMap<>(statesAfterDelay)); // Post update
					}
				}, REMOVAL_DELAY_MS, TimeUnit.MILLISECONDS);
			} else if (newState == NodeState.ON_CONNECTED || newState == NodeState.ON_CONNECTION_FAILED) {
				/*
				 * When a connection is established (and persisted in DB), or explicitly failed,
				 * remove it from transient states. The DB LiveData will now handle the connected state.
				 * Or if failed, we don't want to keep a 'pending' transient state.
				 */
				currentStates.remove(addressName);
				transientNodeStatesLiveData.postValue(new ConcurrentHashMap<>(currentStates)); // Post a copy
			} else {
				// For other transient states (ON_ENDPOINT_FOUND, ON_CONNECTION_INITIATED),
				// add or update them.
				currentStates.put(addressName, newState);
				transientNodeStatesLiveData.postValue(new ConcurrentHashMap<>(currentStates)); // Post a copy to trigger observers
			}
		});
	}

	/**
	 * Clears all transient states. Useful on application shutdown or if a full refresh is needed.
	 */
	public NodeStateRepository clearTransientStates() {
		executor.execute(() -> {
			Log.d(TAG, "Clearing all transient node states.");
			transientNodeStatesLiveData.postValue(new ConcurrentHashMap<>()); // Set to empty map
		});
		return this;
	}

	/**
	 * Shuts down the internal executor service. Call this when the application
	 * is being fully destroyed (e.g., in Application.onTerminate()).
	 */
	public void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException ex) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
		Log.i(TAG, "NodeStateRepository executor shut down.");
	}
}