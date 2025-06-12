package org.sedo.satmesh.ui;

import android.app.Application;
import android.util.Log;
import android.view.View;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeState;
import org.sedo.satmesh.ui.data.NodeTransientState;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NearbyDiscoveryViewModel extends AndroidViewModel {

	private static final String TAG = "NearbyDiscoveryVM";

	private final MediatorLiveData<List<Node>> displayNodeListLiveData = new MediatorLiveData<>();
	private final NodeTransientStateRepository nodeStateRepository;
	private final MutableLiveData<Integer> recyclerVisibility = new MutableLiveData<>();
	private final MutableLiveData<DescriptionState> descriptionState = new MutableLiveData<>();
	private final MutableLiveData<Integer> emptyStateTextView = new MutableLiveData<>();
	private final MutableLiveData<Integer> progressBar = new MutableLiveData<>();

	private final NearbyManager nearbyManager;
	// Executor for ViewModel specific background tasks that are not handled by repositories
	private final ExecutorService viewModelExecutor = Executors.newSingleThreadExecutor();
	private String hostDeviceName;
	private boolean addToBackStack;

	protected NearbyDiscoveryViewModel(@NonNull Application application) {
		super(application);
		// This map still holds transient states not directly in the DB Node model
		NodeRepository nodeRepository = new NodeRepository(application);
		nodeStateRepository = NodeTransientStateRepository.getInstance();
		nearbyManager = NearbyManager.getInstance();

		// Observe the connected nodes from the database
		LiveData<List<Node>> connectedNodesFromDb = nodeRepository.getConnectedNode();
		// Observe data from NodeStateRepository (transient UI states)
		LiveData<Map<String, NodeTransientState>> transientStatesFromRepo = nodeStateRepository.getTransientNodeStates();

		// Combine DB nodes with transient states
		// Use MediatorLiveData to combine these two sources
		displayNodeListLiveData.addSource(connectedNodesFromDb, dbNodes -> {
			Log.d(TAG, "Mediator: DB connected nodes updated.");
			// When DB nodes change, combine them with current transient states
			updateDisplayNodes(dbNodes, transientStatesFromRepo.getValue());
		});

		displayNodeListLiveData.addSource(transientStatesFromRepo, transientStates -> {
			Log.d(TAG, "Mediator: Transient states updated.");
			// When transient states change, combine them with current DB nodes
			updateDisplayNodes(connectedNodesFromDb.getValue(), transientStates);
		});

		// Initialize UI states
		recyclerVisibility.setValue(View.GONE);
		emptyStateTextView.setValue(View.GONE);
		progressBar.setValue(View.VISIBLE);
		descriptionState.setValue(new DescriptionState(R.color.white, getApplication().getString(R.string.nearby_discovery_description)));
	}

	public NearbyManager getNearbyManager() {
		return nearbyManager;
	}

	public LiveData<List<Node>> getDisplayNodeListLiveData() {
		return displayNodeListLiveData;
	}

	public MutableLiveData<Integer> getEmptyStateTextView() {
		return emptyStateTextView;
	}

	public MutableLiveData<Integer> getProgressBar() {
		return progressBar;
	}

	public MutableLiveData<Integer> getRecyclerVisibility() {
		return recyclerVisibility;
	}

	public MutableLiveData<DescriptionState> getDescriptionState() {
		return descriptionState;
	}

	public String getHostDeviceName() {
		return hostDeviceName;
	}

	public void setHostDeviceName(String hostDeviceName) {
		this.hostDeviceName = hostDeviceName;
	}

	public boolean isAddToBackStack() {
		return addToBackStack;
	}

	public void setAddToBackStack(boolean addToBackStack) {
		this.addToBackStack = addToBackStack;
	}

	public NodeState getStateForNode(Node node) {
		// Retrieve state from NodeStateRepository first (for transient states)
		Map<String, NodeTransientState> transientStates = nodeStateRepository.getTransientNodeStates().getValue();
		NodeTransientState state;
		if (transientStates != null && (state = transientStates.get(node.getAddressName())) != null) {
			return state.connectionState;
		}
		// If not in transient states, use the DB's connected status
		return node.isConnected() ? NodeState.ON_CONNECTED : NodeState.ON_ENDPOINT_FOUND;
	}

	/**
	 * Called to (re)load the list of nodes for display.
	 * This method now primarily triggers a refresh of initial transient states
	 * from NearbyManager at load time and relies on LiveData observation.
	 */
	public void load() {
		Log.d(TAG, "Loading initial node states for display.");
		/*
		 * When load is called, we should ensure NodeStateRepository has latest
		 * known transient states at startup (e.g., pending connections from NearbyManager).
		 * This is important because NodeStateRepository's internal map might be empty
		 * on initial ViewModel creation if the service hasn't updated it yet for pending connections.
		 */
		nodeStateRepository.clearTransientStates(); // Clear old transient states on fresh load

		/*
		 * Populate NodeStateRepository with currently known (pending/incoming) endpoints from NearbyManager.
		 * This makes sure these are displayed immediately.
		 */
		for (String pendingAddress : nearbyManager.getAllPendingAddressName()) {
			nodeStateRepository.updateTransientNodeState(pendingAddress, NodeState.ON_CONNECTION_INITIATED);
		}
		for (String incomingAddress : nearbyManager.getAllIncomingAddressName()) {
			nodeStateRepository.updateTransientNodeState(incomingAddress, NodeState.ON_CONNECTION_INITIATED);
		}
		// The MediatorLiveData will automatically combine these updates with DB changes.

		/*
		 * The progressBar is hidden by updateUiVisibility, which is called when the list is updated.
		 * But we can ensure it's visible while loading:
		 * Trigger a fresh update to display the combined list
		 */
		progressBar.postValue(View.VISIBLE);
	}

	/**
	 * Helper method to combine nodes from DB and transient states for display.
	 * This method is triggered by changes in either the connected nodes from DB
	 * or the transient states from NodeStateRepository.
	 *
	 * @param dbNodes         The current list of connected nodes from the database.
	 * @param transientStates The current map of transient states from NodeStateRepository.
	 */
	private void updateDisplayNodes(@Nullable List<Node> dbNodes, @Nullable Map<String, NodeTransientState> transientStates) {
		viewModelExecutor.execute(() -> { // Perform combining logic on a background thread
			Map<String, Node> seenNodes = new HashMap<>(); // To handle uniqueness

			// 1. Add all connected nodes from the database
			if (dbNodes != null) {
				for (Node node : dbNodes) {
					seenNodes.put(node.getAddressName(), node);
				}
			}

			// 2. Overlay or add transient states
			if (transientStates != null) {
				for (Map.Entry<String, NodeTransientState> entry : transientStates.entrySet()) {
					String addressName = entry.getKey();
					NodeTransientState state = entry.getValue();

					if (seenNodes.containsKey(addressName)) {
						/*
						 * Node is already in the list (from DB, meaning connected).
						 * Only override if the transient state implies a temporary override
						 * (e.g., ON_DISCONNECTED for a briefly disconnected but still in DB node).
						 * Otherwise, connected state from DB usually takes precedence for display.
						 */
						if (state.connectionState == NodeState.ON_DISCONNECTED) {
							Node nodeInList = seenNodes.get(addressName);
							Objects.requireNonNull(nodeInList).setConnected(false); // Mark as not connected for UI purposes
							// The NodeStateRepository is responsible for eventually removing this if truly lost.
							Log.d(TAG, "Transient: Marking " + addressName + " as temporarily disconnected.");
						}
						/*
						 * For other states like ON_ENDPOINT_FOUND or ON_CONNECTION_INITIATED,
						 * if the node is already connected (from DB), we typically show it as connected.
						 * So, no change needed here if already in seenNodes.
						 */
					} else {
						// This node is not in the DB's connected list.
						// It must be a newly discovered, pending, or failed-to-connect node.
						Node transientNode = new Node();
						transientNode.setAddressName(addressName);
						transientNode.setConnected(false); // It's not connected yet
						seenNodes.put(addressName, transientNode); // Add to seen to prevent duplicates
						Log.d(TAG, "Transient: Adding " + addressName + " with state " + state + ".");
					}
				}
			}

			// Ensure unique nodes and apply sorting if desired
			List<Node> dedupedAndSortedList = new ArrayList<>(seenNodes.values());
			dedupedAndSortedList.sort(Comparator.comparing(Node::getAddressName));

			displayNodeListLiveData.postValue(dedupedAndSortedList);
			updateUiVisibility(dedupedAndSortedList.isEmpty());
		});
	}

	/**
	 * Updates the visibility of the RecyclerView and empty state TextView based on the list content.
	 *
	 * @param isEmpty true if the list of nodes is empty, false otherwise.
	 */
	private void updateUiVisibility(boolean isEmpty) {
		if (isEmpty) {
			recyclerVisibility.postValue(View.GONE);
			emptyStateTextView.postValue(View.VISIBLE);
		} else {
			recyclerVisibility.postValue(View.VISIBLE);
			emptyStateTextView.postValue(View.GONE);
		}
		progressBar.postValue(View.GONE); // Always hide progress bar after loading
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		viewModelExecutor.shutdown();
	}

	public static class DescriptionState {
		public final @ColorRes int color;
		public final String text;

		public DescriptionState(@ColorRes int color, String text) {
			this.color = color;
			this.text = text;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			DescriptionState that = (DescriptionState) o;
			return color == that.color && Objects.equals(text, that.text);
		}

		@Override
		public int hashCode() {
			return Objects.hash(color, text);
		}
	}
}
