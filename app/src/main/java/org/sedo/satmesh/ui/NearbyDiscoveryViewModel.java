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
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.signal.store.AndroidSessionStore;
import org.sedo.satmesh.ui.data.NodeDiscoveryItem;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeTransientState;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

public class NearbyDiscoveryViewModel extends AndroidViewModel {

	private static final String TAG = "NearbyDiscoveryVM";

	// The single LiveData that the UI observes
	private final MediatorLiveData<List<NodeDiscoveryItem>> displayNodeListLiveData = new MediatorLiveData<>();

	// LiveData for UI state
	private final MutableLiveData<Integer> recyclerVisibility = new MutableLiveData<>();
	private final MutableLiveData<DescriptionState> descriptionState = new MutableLiveData<>();
	private final MutableLiveData<Integer> emptyStateTextView = new MutableLiveData<>();
	private final MutableLiveData<Integer> progressBar = new MutableLiveData<>();

	// Repositories
	private final NodeRepository nodeRepository;
	private final AndroidSessionStore sessionStore;

	// Nearby manager
	private final NearbyManager nearbyManager;
	// Executor for ViewModel specific background tasks that are not handled by repositories
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	// Internal LiveData to hold the latest values from each source
	private final @NonNull LiveData<Map<String, NodeTransientState>> transientStatesSource;
	private LiveData<List<Node>> nodesSource;
	private LiveData<List<String>> secureSessionsSource;

	// Internal variables to store the latest values received from each source
	private Map<String, NodeTransientState> currentTransientStates;
	private List<Node> currentNodes;
	private List<String> currentSecureSessions;

	// Host device address name
	private String hostDeviceName;

	protected NearbyDiscoveryViewModel(@NonNull Application application) {
		super(application);
		nodeRepository = new NodeRepository(application);
		sessionStore = AndroidSessionStore.getInstance();
		nearbyManager = NearbyManager.getInstance();
		transientStatesSource = NodeTransientStateRepository.getInstance().getTransientNodeStates();
		displayNodeListLiveData.addSource(transientStatesSource, this::onTransientStateChanged);

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
		onTransientStateChanged(transientStatesSource.getValue());
	}

	/**
	 * Helper method to engage combining nodes from DB and transient states for display.
	 * This method is triggered by changes in the transient states from {@code NodeTransientStateRepository}.
	 *
	 * @param transientStates The current map of transient states from {@code NodeTransientStateRepository}.
	 */
	private void onTransientStateChanged(@Nullable Map<String, NodeTransientState> transientStates) {
		Log.d(TAG, "Transient states updated in ViewModel: " + (transientStates != null ? transientStates.size() : "0") + " nodes.");
		currentTransientStates = transientStates;
		// When transientStates change, we need to re-evaluate the nodes and secure sessions LiveData
		// as their queries depend on the keys from transientStates.

		// Remove old nodesSource and secureSessionsSource if they exist
		if (nodesSource != null) {
			displayNodeListLiveData.removeSource(nodesSource);
		}
		if (secureSessionsSource != null) {
			displayNodeListLiveData.removeSource(secureSessionsSource);
		}

		if (transientStates != null && !transientStates.isEmpty()) {
			List<String> addresses = new ArrayList<>(transientStates.keySet());

			// Create new LiveData for nodes and add as source
			nodesSource = nodeRepository.getNodesByAddressName(addresses);
			displayNodeListLiveData.addSource(nodesSource, nodes -> {
				Log.d(TAG, "Nodes updated in ViewModel: " + (nodes != null ? nodes.size() : "0") + " nodes.");
				currentNodes = nodes;
				combineAllSources(); // Trigger combination when nodes update
			});

			// Create new LiveData for secure sessions and add as source
			List<String> signalAddresses = addresses.stream()
					.map(SignalManager::getAddress)
					.map(UiUtils::getAddressKey)
					.collect(Collectors.toList());
			secureSessionsSource = sessionStore.filterSecuredSessionAddressNames(signalAddresses);
			displayNodeListLiveData.addSource(secureSessionsSource, secureSessions -> {
				Log.d(TAG, "Secure sessions updated in ViewModel: " + (secureSessions != null ? secureSessions.size() : "0") + " secure sessions.");
				currentSecureSessions = secureSessions;
				combineAllSources(); // Trigger combination when secure sessions update
			});
		} else {
			// If no transient states, clear current data and post empty list
			currentNodes = null;
			currentSecureSessions = null;
		}
		combineAllSources(); // Also call here in case only transientStates changed or became empty
	}

	// This method combines the latest values from all sources
	private void combineAllSources() {
		updateUiVisibility(currentTransientStates == null || currentTransientStates.isEmpty());
		// Ensure all necessary data is available before combining
		if (currentTransientStates == null) {
			displayNodeListLiveData.postValue(Collections.emptyList());
			return;
		}

		List<NodeDiscoveryItem> combinedList = new ArrayList<>();
		Map<String, Node> nodeMap = currentNodes != null ?
				currentNodes.stream().collect(Collectors.toMap(Node::getAddressName, Function.identity())) :
				Collections.emptyMap();
		Set<String> securedAddressNames = currentSecureSessions != null ?
				new HashSet<>(currentSecureSessions) :
				Collections.emptySet();

		for (Map.Entry<String, NodeTransientState> entry : currentTransientStates.entrySet()) {
			String addressName = entry.getKey();
			NodeTransientState state = entry.getValue();

			Node node = nodeMap.get(addressName);
			// If node is not found in currentNodes, create a dummy node with just the addressName
			if (node == null) {
				node = new Node();
				node.setAddressName(addressName);
			}

			boolean isSecured = securedAddressNames.contains(addressName);
			combinedList.add(new NodeDiscoveryItem(node, state.connectionState, isSecured));
		}

		// Sort the list if necessary
		combinedList.sort(Comparator.comparing(NodeDiscoveryItem::getAddressName));
		displayNodeListLiveData.postValue(combinedList);
		Log.d(TAG, "Combined list updated in ViewModel: " + combinedList.size() + " nodes.");
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
		// Remove all sources from MediatorLiveData to prevent memory leaks
		displayNodeListLiveData.removeSource(transientStatesSource);
		if (nodesSource != null) {
			displayNodeListLiveData.removeSource(nodesSource);
		}
		if (secureSessionsSource != null) {
			displayNodeListLiveData.removeSource(secureSessionsSource);
		}
		executor.shutdown();
	}

	public record DescriptionState(@ColorRes int color, String text) {
	}
}
