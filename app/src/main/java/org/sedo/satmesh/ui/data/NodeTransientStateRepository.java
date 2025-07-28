package org.sedo.satmesh.ui.data;

import android.util.Log;

import androidx.annotation.NonNull;
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
public class NodeTransientStateRepository {

	private static final String TAG = "NodeStateRepository";
	/**
	 * Number of milliseconds for temporary states like ON_DISCONNECTED.
	 */
	private static final long REMOVAL_DELAY_MS = 5000; // 5 seconds
	private static volatile NodeTransientStateRepository INSTANCE; // Singleton instance
	/*
	 * MutableLiveData to hold the map of transient node states.
	 * Key: Node's addressName, Value: NodeTransientState
	 * Initialize with an empty ConcurrentHashMap to be thread-safe for internal map operations.
	 */
	private final MutableLiveData<Map<String, NodeTransientState>> transientNodeStatesLiveData = new MutableLiveData<>(new ConcurrentHashMap<>());

	// Executor for handling state updates and timed removals to avoid blocking the main thread.
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	/**
	 * Provides the singleton instance of the NodeStateRepository.
	 *
	 * @return The singleton instance of NodeStateRepository.
	 */
	public static NodeTransientStateRepository getInstance() {
		if (INSTANCE == null) {
			synchronized (NodeTransientStateRepository.class) { // Double-checked locking for thread safety
				if (INSTANCE == null) {
					INSTANCE = new NodeTransientStateRepository();
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
	@NonNull
	public LiveData<Map<String, NodeTransientState>> getTransientNodeStates() {
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
	public void updateTransientNodeState(@NonNull String addressName, @NonNull NodeState newState) {
		executor.execute(() -> {
			Map<String, NodeTransientState> tmp = transientNodeStatesLiveData.getValue();
			Map<String, NodeTransientState> currentStates;
			if (tmp == null) {
				// This should not happen with initial ConcurrentHashMap, but as a safeguard.
				currentStates = new ConcurrentHashMap<>();
				transientNodeStatesLiveData.postValue(currentStates);
			} else {
				currentStates = tmp;
			}

			NodeTransientState ts = currentStates.get(addressName);
			NodeTransientState transientState;
			if (ts == null) {
				transientState = new NodeTransientState(newState);
			} else {
				ts.connectionState = newState;
				transientState = ts;
			}
			Log.d(TAG, "Updating transient state for " + addressName + " from " + (ts != null ? ts.connectionState : "null") + " to " + newState);
			currentStates.put(addressName, transientState);
			transientNodeStatesLiveData.postValue(new ConcurrentHashMap<>(currentStates)); // Post a copy to trigger observers

			if (newState == NodeState.ON_DISCONNECTED) {
				// For disconnected state, add it and schedule a removal after a delay.
				// This allows the UI to show "disconnected" briefly before potentially removing it.
				executor.schedule(() -> {
					// Only remove if still in ON_DISCONNECTED state (i.e., not reconnected)
					Map<String, NodeTransientState> statesAfterDelay = transientNodeStatesLiveData.getValue();
					NodeTransientState stateAfterDelay = statesAfterDelay.get(addressName);
					if (stateAfterDelay != null && stateAfterDelay.connectionState == NodeState.ON_DISCONNECTED) {
						Log.d(TAG, "Removing " + addressName + " from transient states after delay.");
						statesAfterDelay.remove(addressName);
						transientNodeStatesLiveData.postValue(new ConcurrentHashMap<>(statesAfterDelay)); // Post update
					}
				}, REMOVAL_DELAY_MS, TimeUnit.MILLISECONDS);
			}
		});
	}

	/**
	 * Clears all transient states. Useful on application shutdown or if a full refresh is needed.
	 */
	public NodeTransientStateRepository clearTransientStates() {
		if (!executor.isShutdown() && !executor.isTerminated()) {
			executor.execute(() -> {
				Log.d(TAG, "Clearing all transient node states.");
				transientNodeStatesLiveData.postValue(new ConcurrentHashMap<>()); // Set to empty map
			});
		}
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