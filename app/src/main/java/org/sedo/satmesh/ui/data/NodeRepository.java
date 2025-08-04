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

	/**
	 * Updates a node in the database.
	 * The result of the operation is returned via a callback.
	 * This method runs operations on the executor.
	 *
	 * @param node     The node to update.
	 * @param callback The callback to be invoked with the result of the operation.
	 */
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

	/**
	 * Updates a node in the database.
	 * This method runs operations on the executor.
	 *
	 * @param node The node to update.
	 */
	public void update(@NonNull Node node) {
		executor.execute(() -> dao.update(node));
	}

	/**
	 * Finds a node by its ID and returns it as a LiveData object.
	 *
	 * @param nodeId The ID of the node to find.
	 * @return A LiveData object containing the node.
	 */
	public LiveData<Node> findLiveNode(long nodeId) {
		return dao.getNodeById(nodeId);
	}

	/**
	 * Retrieves a LiveData list of Node objects from the database that match the given address names.
	 *
	 * @param addresses A list of address names to search for.
	 * @return A LiveData object containing a list of matching Node objects.
	 */
	public LiveData<List<Node>> getNodesByAddressName(List<String> addresses) {
		return dao.getNodesByAddressName(addresses);
	}

	/**
	 * Finds a node by its ID and returns it synchronously.
	 *
	 * @param nodeId The ID of the node to find.
	 * @return The node, or null if not found.
	 */
	public Node findNodeSync(long nodeId) {
		return dao.getNodeByIdSync(nodeId);
	}

	/**
	 * Finds a node by its address name and returns it synchronously.
	 *
	 * @param addressName The address name of the node to find.
	 * @return The node, or null if not found.
	 */
	public Node findNodeSync(String addressName) {
		return dao.getNodeByAddressNameSync(addressName);
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

	/**
	 * Finds a list of address names for a given list of node IDs.
	 *
	 * @param ids The list of node IDs.
	 * @return A list of address names.
	 */
	public List<String> findAddressesForSync(List<Long> ids) {
		return dao.findAddressesFor(ids);
	}

	/**
	 * Callback interface for asynchronous node operations.
	 *
	 * @author hsedo777
	 */
	public interface NodeCallback {
		/**
		 * Called when the node is ready.
		 *
		 * @param node The node that is ready.
		 */
		void onNodeReady(@NonNull Node node);

		/**
		 * Called when an error occurs during the node operation.
		 *
		 * @param errorMessage The error message.
		 */
		void onError(@NonNull String errorMessage);
	}
}

