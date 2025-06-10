package org.sedo.satmesh.ui.data;

import android.content.Context;

import androidx.annotation.NonNull;
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
	 * Insert new Node
	 */
	public void insertNode(@NonNull Node node, @NonNull Consumer<Boolean> callback) {
		try {
			executor.execute(() -> {
				node.setId(dao.insert(node));
				callback.accept(true);
			});
		} catch (Exception ignored) {
			callback.accept(false);
		}
	}

	public void update(@NonNull Node node) {
		executor.execute(() -> dao.update(node));
	}

	public Node findNode(long nodeId) {
		return dao.getNodeById(nodeId);
	}

	public Node findNode(String addressName) {
		return dao.getNodeByAddressName(addressName);
	}

	public LiveData<List<Node>> getConnectedNode(){
		return dao.getConnectedNode();
	}
}
