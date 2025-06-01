package org.sedo.satmesh.ui.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.model.NodeState;

import java.util.ArrayList;
import java.util.List;

public class NearbyDiscoveryAdapter extends RecyclerView.Adapter<NearbyDiscoveryAdapter.NodeViewHolder> {

	private final Context context;
	private final List<Node> nodeList = new ArrayList<>();
	private final NodeStateProvider stateProvider;

	public interface NodeStateProvider {
		NodeState getNodeState(@NonNull Node node);
	}

	public NearbyDiscoveryAdapter(Context context, NodeStateProvider stateProvider) {
		this.context = context;
		this.stateProvider = stateProvider;
	}

	public void addOrUpdateNode(@NonNull Node newNode) {
		for (int i = 0; i < nodeList.size(); i++) {
			Node existingNode = nodeList.get(i);
			if (existingNode.getAddressName().equals(newNode.getAddressName())) {
				nodeList.set(i, newNode);
				notifyItemChanged(i);
				return;
			}
		}
		nodeList.add(newNode);
		notifyItemInserted(nodeList.size() - 1);
	}

	public void removeNode(@NonNull String addressName) {
		for (int i = 0; i < nodeList.size(); i++) {
			if (nodeList.get(i).getAddressName().equals(addressName)) {
				nodeList.remove(i);
				notifyItemRemoved(i);
				return;
			}
		}
	}

	@SuppressLint("NotifyDataSetChanged")
	public void clear() {
		nodeList.clear();
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public NodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(context).inflate(R.layout.item_nearby_node, parent, false);
		return new NodeViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull NodeViewHolder holder, int position) {
		Node node = nodeList.get(position);
		holder.bind(node, stateProvider.getNodeState(node));
	}

	@Override
	public int getItemCount() {
		return nodeList.size();
	}

	public static class NodeViewHolder extends RecyclerView.ViewHolder {
		private final TextView nameTextView;
		private final View statusIndicator;

		public NodeViewHolder(@NonNull View itemView) {
			super(itemView);
			nameTextView = itemView.findViewById(R.id.node_display_name);
			statusIndicator = itemView.findViewById(R.id.status_indicator);
		}

		public void bind(Node node, NodeState state) {
			nameTextView.setText(node.getAddressName());
			int color = ContextCompat.getColor(itemView.getContext(), state.getColorResId());
			statusIndicator.getBackground().setTint(color);
		}
	}
}
