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
import org.sedo.satmesh.ui.data.NodeDiscoveryItem;
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

	private final MediatorLiveData<List<NodeDiscoveryItem>> displayNodeListLiveData = new MediatorLiveData<>();
	private final MutableLiveData<Integer> recyclerVisibility = new MutableLiveData<>();
	private final MutableLiveData<DescriptionState> descriptionState = new MutableLiveData<>();
	private final MutableLiveData<Integer> emptyStateTextView = new MutableLiveData<>();
	private final MutableLiveData<Integer> progressBar = new MutableLiveData<>();

	private final NodeRepository nodeRepository;
	private final NearbyManager nearbyManager;
	// Executor for ViewModel specific background tasks that are not handled by repositories
	private final ExecutorService viewModelExecutor = Executors.newSingleThreadExecutor();
	private String hostDeviceName;
	private boolean addToBackStack;

	protected NearbyDiscoveryViewModel(@NonNull Application application) {
		super(application);
		// This map still holds transient states not directly in the DB Node model
		nodeRepository = new NodeRepository(application);
		NodeTransientStateRepository nodeStateRepository = NodeTransientStateRepository.getInstance();
		nearbyManager = NearbyManager.getInstance();

		// Observe the connected nodes from the database
		LiveData<List<Node>> connectedNodesFromDb = nodeRepository.getConnectedNode();

		// Combine DB nodes with transient states
		// Use MediatorLiveData to combine these two sources
		displayNodeListLiveData.addSource(connectedNodesFromDb, dbNodes -> {
			Log.d(TAG, "Mediator: DB connected nodes updated.");
			// When DB nodes change, combine them with current transient states
			updateDisplayNodes(dbNodes, nodeStateRepository.getTransientNodeStates().getValue());
		});

		displayNodeListLiveData.addSource(nodeStateRepository.getTransientNodeStates(), transientStates -> {
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

	public LiveData<List<NodeDiscoveryItem>> getDisplayNodeListLiveData() {
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
			Map<String, NodeDiscoveryItem> seenNodes = new HashMap<>(); // To handle uniqueness

			// 1. Add all connected nodes from the database
			if (dbNodes != null) {
				for (Node node : dbNodes) {
					seenNodes.put(node.getAddressName(), new NodeDiscoveryItem(node, null));
				}
			}

			// 2. Overlay or add transient states
			if (transientStates != null) {
				for (Map.Entry<String, NodeTransientState> entry : transientStates.entrySet()) {
					String addressName = entry.getKey();
					NodeTransientState state = entry.getValue();
					NodeDiscoveryItem nodeInList = seenNodes.get(addressName);

					if (nodeInList != null) {
						/*
						 * Node is already in the list (from DB, meaning connected).
						 * Only override if the transient state implies a temporary override
						 * (e.g., ON_DISCONNECTED for a briefly disconnected but still in DB node).
						 * Otherwise, connected state from DB usually takes precedence for display.
						 */
						if (state.connectionState == NodeState.ON_DISCONNECTED) {
							nodeInList.node.setConnected(false); // Mark as not connected for UI purposes
							// The `NearbySignalMessenger` is responsible for eventually removing this if truly lost.
							Log.d(TAG, "Transient: Marking " + addressName + " as temporarily disconnected.");
						}
					} else {
						// This node is not in the DB's connected list.
						// It must be a newly discovered, pending, or failed-to-connect node.
						Node node;
						node = nodeRepository.findNodeSync(addressName);
						if (node == null)
							node = new Node();
						/*
						 * If the node fetched from database is not null, we don't need to update
						 * its state here. We just use it. Its setting up to date is covered by
						 * `NearbySignalMessenger`
						 */
						node.setAddressName(addressName);
						node.setConnected(false); // It's not connected yet
						nodeInList = new NodeDiscoveryItem(node, null);
						seenNodes.put(addressName, nodeInList); // Add to seen to prevent duplicates
						Log.d(TAG, "Transient: Adding " + addressName + " with state " + state + ".");
					}
					nodeInList.state = state.connectionState;
				}
			}

			// Ensure unique nodes and apply sorting if desired
			List<NodeDiscoveryItem> dedupedAndSortedList = new ArrayList<>(seenNodes.values());
			dedupedAndSortedList.sort(Comparator.comparing(NodeDiscoveryItem::getAddressName));

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
