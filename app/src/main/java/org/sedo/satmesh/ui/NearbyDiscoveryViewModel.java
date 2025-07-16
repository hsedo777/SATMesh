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
import org.sedo.satmesh.nearby.NearbySignalMessenger;
import org.sedo.satmesh.ui.data.NodeDiscoveryItem;
import org.sedo.satmesh.ui.data.NodeRepository;
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
	private final NodeTransientStateRepository nodeStateRepository;
	// Executor for ViewModel specific background tasks that are not handled by repositories
	private final ExecutorService viewModelExecutor = Executors.newSingleThreadExecutor();
	private String hostDeviceName;

	protected NearbyDiscoveryViewModel(@NonNull Application application) {
		super(application);
		// This map still holds transient states not directly in the DB Node model
		nodeRepository = new NodeRepository(application);
		nodeStateRepository = NodeTransientStateRepository.getInstance();
		nearbyManager = NearbyManager.getInstance();

		displayNodeListLiveData.addSource(nodeStateRepository.getTransientNodeStates(), transientStates -> {
			Log.d(TAG, "Mediator: Transient states updated.");

			// When transient states change, combine them with current DB nodes
			updateDisplayNodes(transientStates);
		});

		// Initialize UI states
		recyclerVisibility.setValue(View.GONE);
		emptyStateTextView.setValue(View.GONE);
		progressBar.setValue(View.VISIBLE);
		descriptionState.setValue(new DescriptionState(R.color.colorOnSecondary, getApplication().getString(R.string.nearby_discovery_description)));
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

	/**
	 * Force reloading nodes in the view
	 */
	public void reloadNodes() {
		nearbyManager.startDiscovery();
		updateDisplayNodes(nodeStateRepository.getTransientNodeStates().getValue());
	}

	/**
	 * Helper method to combine nodes from DB and transient states for display.
	 * This method is triggered by changes in either the connected nodes from DB
	 * or the transient states from NodeStateRepository.
	 *
	 * @param transientStates The current map of transient states from NodeStateRepository.
	 */
	private void updateDisplayNodes(@Nullable Map<String, NodeTransientState> transientStates) {
		viewModelExecutor.execute(() -> { // Perform logic on a background thread
			Map<String, NodeDiscoveryItem> seenNodes = new HashMap<>(); // To handle uniqueness

			// 1. Overlay or add transient states
			if (transientStates != null) {
				for (Map.Entry<String, NodeTransientState> entry : transientStates.entrySet()) {
					String addressName = entry.getKey();
					NodeTransientState state = entry.getValue();

					Node tmp = nodeRepository.findNodeSync(addressName);
					Node node;
					if (tmp == null) {
						node = new Node();
						// Do not persist the node, it is the job of `NearbySignalMessenger`
						node.setAddressName(addressName);
					} else {
						node = tmp;
					}
					boolean isSecure = NearbySignalMessenger.getInstance().hasSession(addressName);
					NodeDiscoveryItem nodeInList = new NodeDiscoveryItem(node, state.connectionState, isSecure);
					seenNodes.put(addressName, nodeInList);
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
