package org.sedo.satmesh.ui.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.NodeDao;

import java.util.List;
import java.util.concurrent.Executor;

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

	public void update(@NonNull Node node) {
		executor.execute(() -> dao.update(node));
	}

	public LiveData<Node> findLiveNode(long nodeId) {
		return dao.getNodeByIdAsLiveData(nodeId);
	}

	public Node findNode(String addressName) {
		return dao.getNodeByAddressName(addressName);
	}

	public LiveData<List<Node>> getConnectedNode(){
		return dao.getConnectedNode();
	}
}
