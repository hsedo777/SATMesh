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

	public void insert(@NonNull Node node) {
		executor.execute(() -> node.setId(dao.insert(node)));
	}

	/**
	 *  Process insertion operation and give control to operate after operation finalized.
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

	public void update(@NonNull Node node) {
		executor.execute(() -> dao.update(node));
	}

	public LiveData<Node> findLiveNode(long nodeId) {
		return dao.getNodeById(nodeId);
	}

	public Node findNodeSync(String addressName) {
		return dao.getNodeByAddressNameSync(addressName);
	}

	public LiveData<Node> findNode(String addressName) {
		return dao.getNodeByAddressName(addressName);
	}

	public LiveData<List<Node>> getConnectedNode() {
		return dao.getConnectedNode();
	}

	public void setAllNodesDisconnected() {
		executor.execute(dao::setAllNodesDisconnected);
	}
}
