package org.sedo.satmesh.ui;

import android.app.Application;
import android.view.View;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.google.android.gms.common.api.Status;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.ui.NearbyDiscoveryFragment.DiscoveryFragmentListener;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public class NearbyDiscoveryViewModel extends AndroidViewModel {

	private static final int REMOVING_DElAY = 5000; // 5s

	private final MutableLiveData<List<Node>> nodeListLiveData = new MutableLiveData<>(new ArrayList<>());
	private final MutableLiveData<Integer> recyclerVisibility = new MutableLiveData<>();
	private final MutableLiveData<DescriptionState> descriptionState = new MutableLiveData<>();
	private final MutableLiveData<Integer> emptyStateTextView = new MutableLiveData<>();
	private final MutableLiveData<Integer> progressBar = new MutableLiveData<>();
	private final Map<String, NodeState> nodeStates = new HashMap<>();
	private final NodeRepository nodeRepository;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final NearbyManager.DeviceConnectionListener connectionListener = new NearbyManager.DeviceConnectionListener() {
		@Override
		public void onConnectionInitiated(String endpointId, String deviceAddressName) {
			addOrUpdateNode(getNode(deviceAddressName), NodeState.ON_CONNECTION_INITIATED);
		}

		@Override
		public void onDeviceConnected(String endpointId, String deviceAddressName) {
			addOrUpdateNode(getNode(deviceAddressName), NodeState.ON_CONNECTED);
		}

		@Override
		public void onConnectionFailed(String deviceAddressName, Status status) {
			addOrUpdateNode(getNode(deviceAddressName), NodeState.ON_CONNECTION_FAILED);
		}

		@Override
		public void onDeviceDisconnected(String endpointId, String deviceAddressName) {
			addOrUpdateNode(getNode(deviceAddressName), NodeState.ON_DISCONNECTED);
			executor.execute(() -> {
				try {
					Thread.sleep(REMOVING_DElAY);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				removeNode(deviceAddressName);
			});
		}
	};
	private final NearbyManager.DiscoveringListener discoveringListener = new NearbyManager.DiscoveringListener() {

		@Override
		public void onDiscoveringStarted() {
			// OK, may activate description view on fragment
			descriptionState.postValue(new DescriptionState(R.color.white, getApplication().getString(R.string.nearby_discovery_description)));
		}

		@Override
		public void onDiscoveringFailed(Exception e) {
			descriptionState.postValue(new DescriptionState(R.color.colorError, getApplication().getString(R.string.internal_error)));
			recyclerVisibility.postValue(View.GONE);
		}

		@Override
		public void onEndpointFound(String endpointId, String endpointName) {
			if (getNode(endpointName) == null)
				appendNode(endpointName, NodeState.ON_ENDPOINT_FOUND);
		}

		@Override
		public void onEndpointLost(String endpointId, String endpointName) {
			if (endpointName == null) {
				return;
			}
			Node node = getNode(endpointName);
			if (node != null && getStateForNode(node) != NodeState.ON_CONNECTED)
				addOrUpdateNode(node, NodeState.ON_ENDPOINT_LOST);
		}
	};
	private final NearbyManager nearbyManager;
	private String hostDeviceName;
	private boolean addToBackStack;
	private DiscoveryFragmentListener discoveryListener;

	protected NearbyDiscoveryViewModel(@NonNull Application application) {
		super(application);
		nodeRepository = new NodeRepository(application);
		nearbyManager = NearbyManager.getInstance(getApplication(), "");
		this.nearbyManager.addDiscoveringListener(discoveringListener);
		this.nearbyManager.addDeviceConnectionListener(connectionListener);
	}

	public NearbyManager getNearbyManager() {
		return nearbyManager;
	}

	public MutableLiveData<List<Node>> getNodeListLiveData() {
		return nodeListLiveData;
	}

	public DiscoveryFragmentListener getDiscoveryListener() {
		return discoveryListener;
	}

	public void setDiscoveryListener(DiscoveryFragmentListener discoveryListener) {
		this.discoveryListener = discoveryListener;
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
		return nodeStates.getOrDefault(node.getAddressName(), NodeState.ON_ENDPOINT_FOUND);
	}

	protected void appendNode(String endpointName, NodeState state) {
		Node node = new Node();
		node.setAddressName(endpointName);
		addOrUpdateNode(node, state);
	}

	public void load() {
		nearbyManager.startAdvertising();
		nearbyManager.startDiscovery();

		BiConsumer<String, NodeState> mapper = (address, state) ->{
			Node node = getNode(address);
			if (node != null){
				addOrUpdateNode(node, state);
			} else {
				appendNode(address, state);
			}
		};
		for (String pending : nearbyManager.getAllPendingAddressName()) {
			appendNode(pending, NodeState.ON_ENDPOINT_FOUND);
		}
		for (String incoming : nearbyManager.getAllIncomingAddressName()) {
			mapper.accept(incoming, NodeState.ON_CONNECTION_INITIATED);
		}
		for (String connected : nearbyManager.getAllConnectedAddressName()) {
			mapper.accept(connected, NodeState.ON_CONNECTED);
		}
	}

	public void addOrUpdateNode(Node node, NodeState state) {
		if (node == null) {
			return;
		}
		executor.execute(() -> {
			nodeStates.put(node.getAddressName(), state);
			List<Node> currentList, oldList = nodeListLiveData.getValue();
			if (oldList == null) {
				currentList = new ArrayList<>();
			} else {
				currentList = new ArrayList<>(oldList);
			}

			int index = currentList.indexOf(node);
			// Try to retrieve the node from db
			Node refresh = nodeRepository.findNode(node.getAddressName());
			Node current = refresh == null ? node : refresh;

			if (index != -1) {
				currentList.set(index, current);
			} else {
				currentList.add(current);
			}

			nodeListLiveData.postValue(currentList);
		});
	}

	public void removeNode(String addressName) {
		nodeStates.remove(addressName);
		List<Node> currentList = new ArrayList<>(Objects.requireNonNull(nodeListLiveData.getValue()));
		currentList.removeIf(n -> n.getAddressName().equals(addressName));
		nodeListLiveData.postValue(currentList);
	}

	public Node getNode(String addressName) {
		List<Node> currentList, oldList = nodeListLiveData.getValue();
		if (oldList == null) {
			currentList = new ArrayList<>();
		} else {
			currentList = new ArrayList<>(oldList);
		}
		return currentList.stream().filter(n -> addressName.equals(n.getAddressName())).findFirst().orElse(null);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		discoveryListener = null;
		nearbyManager.stopDiscovery();
		// Advertising may continue to in favor of routing
		nearbyManager.removeDeviceConnectionListener(connectionListener);
		nearbyManager.removeDiscoveringListener(discoveringListener);
		executor.shutdown();
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
