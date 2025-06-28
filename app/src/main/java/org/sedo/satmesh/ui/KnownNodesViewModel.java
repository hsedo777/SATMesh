package org.sedo.satmesh.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.data.NodeRepository;

import java.util.List;

public class KnownNodesViewModel extends AndroidViewModel {

	private final NodeRepository nodeRepository;

	private final MutableLiveData<Long> hostNodeIdLiveData = new MutableLiveData<>();

	private final LiveData<List<Node>> knownNodesExcludingHost;

	public KnownNodesViewModel(@NonNull Application application) {
		super(application);
		this.nodeRepository = new NodeRepository(application);

		knownNodesExcludingHost = Transformations.switchMap(hostNodeIdLiveData, hostNodeId -> {
			if (hostNodeId == null) {
				// Return an empty LiveData list if hostNodeId is not set yet
				return new MutableLiveData<>(java.util.Collections.emptyList());
			}
			return nodeRepository.getKnownNodesExcludingHost(hostNodeId);
		});
	}

	/**
	 * Sets the ID of the current host node. This triggers the fetching of known nodes,
	 * excluding the host node itself.
	 *
	 * @param hostNodeId The ID of the local host node.
	 */
	public void setHostNodeId(long hostNodeId) {
		if (!Long.valueOf(hostNodeId).equals(hostNodeIdLiveData.getValue())) {
			hostNodeIdLiveData.setValue(hostNodeId);
		}
	}

	/**
	 * Returns a LiveData list of all known Node objects, excluding the host node.
	 * Observing this LiveData will provide real-time updates from the database.
	 *
	 * @return A LiveData object containing a list of known Node objects.
	 */
	public LiveData<List<Node>> getKnownNodesExcludingHost() {
		return knownNodesExcludingHost;
	}

	/**
	 * Deletes a list of Node objects from the database.
	 * This operation is delegated to the NodeRepository and runs on a background thread.
	 *
	 * @param nodes The list of Node objects to be deleted.
	 */
	public void deleteNodes(List<Node> nodes) {
		nodeRepository.deleteNodes(nodes);
	}
}