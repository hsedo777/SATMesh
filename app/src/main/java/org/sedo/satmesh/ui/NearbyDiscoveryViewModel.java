package org.sedo.satmesh.ui;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
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
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.signal.store.AndroidSessionStore;
import org.sedo.satmesh.ui.data.NodeDiscoveryItem;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeTransientState;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

public class NearbyDiscoveryViewModel extends AndroidViewModel {

	private static final String TAG = "NearbyDiscoveryVM";

	private final MediatorLiveData<List<NodeDiscoveryItem>> displayNodeListLiveData = new MediatorLiveData<>();
	private final MediatorLiveData<List<Node>> dbNodesLiveDataSource = new MediatorLiveData<>();

	private final MutableLiveData<Integer> recyclerVisibility = new MutableLiveData<>();
	private final MutableLiveData<DescriptionState> descriptionState = new MutableLiveData<>();
	private final MutableLiveData<Integer> emptyStateTextView = new MutableLiveData<>();
	private final MutableLiveData<Integer> progressBar = new MutableLiveData<>();

	private final NodeRepository nodeRepository;
	private final AndroidSessionStore sessionStore;
	private final NodeTransientStateRepository nodeStateRepository;
	private final NearbyManager nearbyManager;
	// Executor for ViewModel specific background tasks that are not handled by repositories
	private final ExecutorService viewModelExecutor = Executors.newSingleThreadExecutor();
	private LiveData<List<Node>> nodesLiveData;
	private LiveData<List<String>> secureSessionsLiveData;
	private String hostDeviceName;

	protected NearbyDiscoveryViewModel(@NonNull Application application) {
		super(application);
		nodeRepository = new NodeRepository(application);
		nodeStateRepository = NodeTransientStateRepository.getInstance();
		sessionStore = AndroidSessionStore.getInstance();
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
		descriptionState.setValue(new DescriptionState(R.color.on_secondary, getApplication().getString(R.string.nearby_discovery_description)));
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

	public synchronized void merge(List<Node> nodes) {
		List<NodeDiscoveryItem> items = displayNodeListLiveData.getValue();
		if (nodes == null || items == null) {
			return;
		}
		Log.d(TAG, "Merging with database nodes: " + nodes.size());
		Map<String, Node> nodeMap = nodes.stream().collect(Collectors.toMap(Node::getAddressName, Function.identity()));
		List<NodeDiscoveryItem> newItems = new ArrayList<>();
		for (NodeDiscoveryItem item : items) {
			Node node = nodeMap.get(item.getAddressName());
			newItems.add(new NodeDiscoveryItem(Objects.requireNonNullElse(node, item.node()), item.state(), item.isSecured()));
		}
		displayNodeListLiveData.postValue(newItems);
	}

	private synchronized void mergeSecureSessions(List<String> addressNames) {
		List<NodeDiscoveryItem> items = displayNodeListLiveData.getValue();
		if (items == null || addressNames == null) {
			return;
		}
		Log.d(TAG, "Merging with secure sessions: " + addressNames.size());
		List<NodeDiscoveryItem> newItems = new ArrayList<>();
		for (NodeDiscoveryItem item : items) {
			newItems.add(new NodeDiscoveryItem(item.node(), item.state(), addressNames.contains(item.getAddressName())));
		}
		displayNodeListLiveData.postValue(newItems);
	}

	/**
	 * Helper method to combine nodes from DB and transient states for display.
	 * This method is triggered by changes in either the connected nodes from DB
	 * or the transient states from NodeStateRepository.
	 *
	 * @param transientStates The current map of transient states from NodeStateRepository.
	 */
	private void updateDisplayNodes(@Nullable Map<String, NodeTransientState> transientStates) {
		List<NodeDiscoveryItem> items = new ArrayList<>(); // To handle uniqueness
		Log.d(TAG, "Reading transientState=" + transientStates);

		// 1. Overlay or add transient states
		if (transientStates != null) {
			viewModelExecutor.execute(() -> {
				for (Map.Entry<String, NodeTransientState> entry : transientStates.entrySet()) {
					String addressName = entry.getKey();
					NodeTransientState state = entry.getValue();

					Node node = new Node();
					// Do not persist the node, it is the job of `NearbySignalMessenger`
					node.setAddressName(addressName);
					NodeDiscoveryItem newItem = new NodeDiscoveryItem(node, state.connectionState, false);
					items.add(newItem);
				}
				items.sort(Comparator.comparing(NodeDiscoveryItem::getAddressName));
				new Handler(Looper.getMainLooper()).post(() -> {
					displayNodeListLiveData.setValue(items);
					updateUiVisibility(items.isEmpty());
					// Load nodes from db
					if (nodesLiveData != null) {
						dbNodesLiveDataSource.removeSource(nodesLiveData);
					}
					List<String> addresses = new ArrayList<>(transientStates.keySet());
					nodesLiveData = nodeRepository.getNodesByAddressName(addresses);
					dbNodesLiveDataSource.addSource(nodesLiveData, dbNodesLiveDataSource::postValue);

					if (secureSessionsLiveData != null) {
						displayNodeListLiveData.removeSource(secureSessionsLiveData);
					}
					List<String> signalAddresses = addresses.stream()
							.map(SignalManager::getAddress)
							.map(UiUtils::getAddressKey)
							.collect(Collectors.toList());
					secureSessionsLiveData = sessionStore.filterSecuredSessionAddressNames(signalAddresses);
					displayNodeListLiveData.addSource(secureSessionsLiveData, this::mergeSecureSessions);
				});
			});
		}
	}

	public MediatorLiveData<List<Node>> getDbNodesLiveDataSource() {
		return dbNodesLiveDataSource;
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
		if (nodesLiveData != null) {
			dbNodesLiveDataSource.removeSource(nodesLiveData);
		}
		if (secureSessionsLiveData != null) {
			displayNodeListLiveData.removeSource(secureSessionsLiveData);
		}
		secureSessionsLiveData = null;
		nodesLiveData = null;
		viewModelExecutor.shutdown();
	}

	public record DescriptionState(@ColorRes int color, String text) {
	}
}
