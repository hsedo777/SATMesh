package org.sedo.satmesh.ui.adapter;

import static org.sedo.satmesh.ui.adapter.NearbyDiscoveryAdapter.NodeViewHolder;

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
import org.sedo.satmesh.ui.data.NodeState;

import java.util.ArrayList;
import java.util.List;

public class NearbyDiscoveryAdapter extends RecyclerView.Adapter<NodeViewHolder> {

	private final Context context;
	private final List<Node> nodeList = new ArrayList<>();
	private final NodeStateProvider stateProvider;
	private final OnChildClickCallback callback;
	private OnNodeClickListener clickListener;

	public NearbyDiscoveryAdapter(Context context, NodeStateProvider stateProvider) {
		this.context = context;
		this.stateProvider = stateProvider;
		callback = new OnChildClickCallback() {
			@Override
			public void onClick(int position) {
				if (clickListener != null) {
					Node node = nodeList.get(position);
					clickListener.onClick(node, stateProvider.getNodeState(node));
				}
			}

			@Override
			public void onLongClick(int position) {
				if (clickListener != null) {
					Node node = nodeList.get(position);
					clickListener.onLongClick(node, stateProvider.getNodeState(node));
				}
			}
		};
	}

	public void attachOnNodeClickListener(@NonNull OnNodeClickListener listener) {
		this.clickListener = listener;
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

	@SuppressLint("NotifyDataSetChanged")
	public void clear() {
		nodeList.clear();
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public NodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(context).inflate(R.layout.item_nearby_node, parent, false);
		return new NodeViewHolder(view, callback);
	}

	@Override
	public void onBindViewHolder(@NonNull NodeViewHolder holder, int position) {
		Node node = nodeList.get(position);
		holder.bind(node, stateProvider.getNodeState(node), position);
	}

	@Override
	public int getItemCount() {
		return nodeList.size();
	}

	public interface NodeStateProvider {
		NodeState getNodeState(@NonNull Node node);
	}

	public interface OnNodeClickListener {
		void onClick(Node node, NodeState state);

		void onLongClick(Node node, NodeState state);
	}

	public interface OnChildClickCallback {
		void onClick(int position);

		void onLongClick(int position);
	}

	public static class NodeViewHolder extends RecyclerView.ViewHolder {
		private final TextView nameTextView;
		private final View statusIndicator;

		public NodeViewHolder(@NonNull View itemView, @NonNull OnChildClickCallback onClick) {
			super(itemView);
			nameTextView = itemView.findViewById(R.id.node_display_name);
			statusIndicator = itemView.findViewById(R.id.status_indicator);
			this.itemView.setOnClickListener(view -> {
				try {
					onClick.onClick((int) view.getTag());
				} catch (Exception ignored) {
				}
			});
			this.itemView.setOnLongClickListener(view -> {
				try {
					onClick.onLongClick((int) view.getTag());
				} catch (Exception ignored) {
				}
				return true;
			});
		}

		public void bind(Node node, NodeState state, int position) {
			itemView.setTag(position);
			String displayName = node.getDisplayName();
			nameTextView.setText(displayName == null ? node.getAddressName() : displayName);
			int color = ContextCompat.getColor(itemView.getContext(), state.getColorResId());
			statusIndicator.getBackground().setTint(color);
		}
	}
}
