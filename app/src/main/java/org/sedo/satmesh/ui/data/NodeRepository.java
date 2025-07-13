package org.sedo.satmesh.ui.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.NodeDao;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class NodeRepository {

	private final NodeDao dao;
	private final Executor executor;

	public NodeRepository(@NonNull Context context) {
		AppDatabase db = AppDatabase.getDB(context);
		dao = db.nodeDao();
		executor = db.getQueryExecutor();
	}

	/**
	 * Process insertion operation and give control to operate after operation finalized.
	 * This help to consume asynchronous operation await.
	 */
	public void insert(@NonNull Node node, @Nullable Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				node.setId(dao.insert(node));
				if (callback != null) {
					callback.accept(true);
				}
			} catch (Exception e) {
				if (callback != null) {
					callback.accept(false);
				}
			}
		});
	}

	public void update(@NonNull Node node, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				dao.update(node);
				callback.accept(true);
			} catch (Exception e) {
				callback.accept(false);
			}
		});
	}

	public void update(@NonNull Node node) {
		executor.execute(() -> dao.update(node));
	}

	public LiveData<Node> findLiveNode(long nodeId) {
		return dao.getNodeById(nodeId);
	}

	public Node findNodeSync(long nodeId) {
		return dao.getNodeByIdSync(nodeId);
	}

	public Node findNodeSync(String addressName) {
		return dao.getNodeByAddressNameSync(addressName);
	}

	public LiveData<List<Node>> getConnectedNode() {
		return dao.getConnectedNode();
	}

	public void setAllNodesDisconnected() {
		executor.execute(dao::setAllNodesDisconnected);
	}

	/**
	 * Finds a node by its address name. If not found, attempts to create it.
	 * The result is returned via a callback. This method runs operations on the executor.
	 *
	 * @param addressName The address name of the node to find or create.
	 * @param callback    The callback to be invoked with the Node or an error message.
	 */
	public void findOrCreateNodeAsync(@NonNull String addressName, @NonNull NodeCallback callback) {
		executor.execute(() -> {
			Node node = findNodeSync(addressName);

			if (node != null) {
				callback.onNodeReady(node);
			} else {
				Node newNode = new Node();
				newNode.setAddressName(addressName);
				insert(newNode, success -> {
					if (success) {
						callback.onNodeReady(newNode);
					} else {
						callback.onError("Failed to insert new node: " + addressName);
					}
				});
			}
		});
	}

	/**
	 * Retrieves a LiveData list of all known Node objects from the database,
	 * excluding the specified host node. The list is ordered by display name.
	 *
	 * @param hostNodeId The ID of the current host node to be excluded.
	 * @return A LiveData object containing a list of known Node objects.
	 */
	public LiveData<List<Node>> getKnownNodesExcludingHost(long hostNodeId) {
		return dao.getKnownNodesExcludingHost(hostNodeId);
	}

	/**
	 * Deletes a list of Node objects from the database.
	 * This operation is performed asynchronously on a background thread.
	 *
	 * @param nodes The list of Node objects to be deleted.
	 */
	public void deleteNodes(List<Node> nodes) {
		executor.execute(() -> dao.delete(nodes));
	}

	public interface NodeCallback {
		void onNodeReady(@NonNull Node node);

		void onError(@NonNull String errorMessage);
	}
}
