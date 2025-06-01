package org.sedo.satmesh.ui;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.model.NodeState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NearbyDiscoveryViewModel extends ViewModel {

	private final MutableLiveData<List<Node>> nodeListLiveData = new MutableLiveData<>(new ArrayList<>());
	private final Map<String, NodeState> nodeStates = new HashMap<>();
	private String hostDeviceName;
	private boolean addToBackStack;

	protected NearbyDiscoveryViewModel(){}

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

	public void addOrUpdateNode(Node node, NodeState state) {
		nodeStates.put(node.getAddressName(), state);
		List<Node> currentList = new ArrayList<>(Objects.requireNonNull(nodeListLiveData.getValue()));

		int index = currentList.indexOf(node);

		if (index != -1) {
			currentList.set(index, node);
		} else {
			currentList.add(node);
		}

		nodeListLiveData.setValue(currentList);
	}

	public void removeNode(String addressName) {
		nodeStates.remove(addressName);
		List<Node> currentList = new ArrayList<>(Objects.requireNonNull(nodeListLiveData.getValue()));
		currentList.removeIf(n -> n.getAddressName().equals(addressName));
		nodeListLiveData.setValue(currentList);
	}

	public Node getNode(String addressName){
		List<Node> currentList = new ArrayList<>(Objects.requireNonNull(nodeListLiveData.getValue()));
		return currentList.stream().filter(n -> addressName.equals(n.getAddressName())).findFirst().orElse(null);
	}

	/**
	 * Observe the state of the nodes list
	 */
	@MainThread
	public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super List<Node>> observer) {
		nodeListLiveData.observe(owner, observer);
	}
}
