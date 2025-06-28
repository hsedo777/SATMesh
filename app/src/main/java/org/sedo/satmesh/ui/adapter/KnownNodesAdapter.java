package org.sedo.satmesh.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.utils.Utils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class KnownNodesAdapter extends ListAdapter<Node, KnownNodesAdapter.KnownNodeViewHolder> {

	// DiffUtil.ItemCallback for efficient list updates
	private static final DiffUtil.ItemCallback<Node> DIFF_CALLBACK = new DiffUtil.ItemCallback<Node>() {
		@Override
		public boolean areItemsTheSame(@NonNull Node oldItem, @NonNull Node newItem) {
			return oldItem.getId().equals(newItem.getId());
		}

		@Override
		public boolean areContentsTheSame(@NonNull Node oldItem, @NonNull Node newItem) {
			return oldItem.equals(newItem);
		}
	};
	private final Set<Long> selectedNodeIds = new HashSet<>();
	private OnItemClickListener itemClickListener;
	private OnItemLongClickListener itemLongClickListener;

	public KnownNodesAdapter() {
		super(DIFF_CALLBACK);
	}

	/**
	 * Sets the listener for single click events on a node item.
	 *
	 * @param listener The listener to be invoked.
	 */
	public void setOnItemClickListener(OnItemClickListener listener) {
		this.itemClickListener = listener;
	}

	/**
	 * Sets the listener for long click events on a node item.
	 *
	 * @param listener The listener to be invoked.
	 */
	public void setOnItemLongClickListener(OnItemLongClickListener listener) {
		this.itemLongClickListener = listener;
	}

	/**
	 * Toggles the selection state of a node and notifies the adapter for UI update.
	 * This method is called from the Fragment/Activity when an item is clicked/long-clicked.
	 *
	 * @param nodeId The ID of the node whose selection state is to be toggled.
	 * @return true if the node is now selected, false otherwise.
	 */
	public boolean toggleSelection(@NonNull Long nodeId) {
		boolean isSelected;
		if (isSelected(nodeId)) {
			selectedNodeIds.remove(nodeId);
			isSelected = false;
		} else {
			selectedNodeIds.add(nodeId);
			isSelected = true;
		}
		// Notify item changed to re-bind and update its selected state visual
		// We iterate through currentList to find the position.
		Node node = getCurrentList().stream().filter(n -> nodeId.equals(n.getId())).findFirst().orElse(null);
		int position = node != null ? getCurrentList().indexOf(node) : -1;
		if (position != -1) {
			notifyItemChanged(position);
		}
		return isSelected;
	}

	/**
	 * Clears all current selections and notifies the adapter to refresh.
	 */
	public void clearSelections() {
		if (!selectedNodeIds.isEmpty()) {
			Set<Long> previouslySelectedIds = new HashSet<>(selectedNodeIds); // Copy to iterate
			selectedNodeIds.clear();
			// Notify all previously selected items to update their state
			for (Long nodeId : previouslySelectedIds) {
				Node node = getCurrentList().stream().filter(n -> nodeId.equals(n.getId())).findFirst().orElse(null);
				int position = node != null ? getCurrentList().indexOf(node) : -1;
				if (position != -1) {
					notifyItemChanged(position);
				}
			}
		}
	}

	/**
	 * Checks if a node is currently selected.
	 *
	 * @param nodeId The ID of the node to check.
	 * @return true if the node is selected, false otherwise.
	 */
	public boolean isSelected(Long nodeId) {
		return selectedNodeIds.contains(nodeId);
	}

	/**
	 * Returns the number of currently selected nodes.
	 *
	 * @return The count of selected nodes.
	 */
	public int getSelectedItemCount() {
		return selectedNodeIds.size();
	}

	/**
	 * Returns a set of IDs of all currently selected nodes.
	 *
	 * @return A {@link Set} containing the IDs of selected nodes.
	 */
	public Set<Long> getSelectedNodeIds() {
		return new HashSet<>(selectedNodeIds); // Return a copy to prevent external modification
	}

	/**
	 * Returns a list of all currently selected Node objects.
	 *
	 * @return A {@link List} containing the selected Node objects.
	 */
	public List<Node> getSelectedNodes() {
		return getCurrentList().stream().filter(node -> selectedNodeIds.contains(node.getId())).collect(Collectors.toList());
	}


	@NonNull
	@Override
	public KnownNodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View itemView = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_node, parent, false);
		return new KnownNodeViewHolder(itemView,
				position -> { // Click callback
					if (itemClickListener != null) {
						itemClickListener.onItemClick(getItem(position));
					}
				},
				position -> { // Long click callback
					if (itemLongClickListener != null) {
						itemLongClickListener.onItemLongClick(getItem(position));
					}
				}
		);
	}

	@Override
	public void onBindViewHolder(@NonNull KnownNodeViewHolder holder, int position) {
		Node currentNode = getItem(position);
		holder.bind(currentNode);
		// Set the selected state of the itemView for visual feedback
		holder.itemView.setSelected(isSelected(currentNode.getId()));
	}

	public interface OnItemClickListener {
		void onItemClick(@NonNull Node node);
	}

	public interface OnItemLongClickListener {
		void onItemLongClick(@NonNull Node node);
	}

	// Helper interfaces for ViewHolder internal click/long-click dispatching
	public interface OnItemClickCallback {
		void onItemClick(int position);
	}

	public interface OnItemLongClickCallback {
		void onItemLongClick(int position);
	}

	/**
	 * ViewHolder for individual known node items.
	 * Binds Node data to the views defined in item_node.xml.
	 */
	public static class KnownNodeViewHolder extends RecyclerView.ViewHolder {
		private final TextView remoteNodeDisplayName;
		private final TextView remoteNodeAddressName;
		private final TextView remoteNodeLastSeen;

		public KnownNodeViewHolder(@NonNull View itemView,
		                           @NonNull OnItemClickCallback clickCallback,
		                           @NonNull OnItemLongClickCallback longClickCallback) {
			super(itemView);
			remoteNodeDisplayName = itemView.findViewById(R.id.text_node_display_name);
			remoteNodeAddressName = itemView.findViewById(R.id.text_node_address_name);
			remoteNodeLastSeen = itemView.findViewById(R.id.text_node_last_seen);

			itemView.setOnClickListener(v -> {
				if (getAdapterPosition() != RecyclerView.NO_POSITION) {
					clickCallback.onItemClick(getAdapterPosition());
				}
			});

			itemView.setOnLongClickListener(v -> {
				if (getAdapterPosition() != RecyclerView.NO_POSITION) {
					longClickCallback.onItemLongClick(getAdapterPosition());
					return true; // Consume the long click event
				}
				return false;
			});
		}

		/**
		 * Binds a Node object to the views in the ViewHolder.
		 *
		 * @param node The Node object to bind.
		 */
		public void bind(@NonNull Node node) {
			remoteNodeDisplayName.setText(node.getNonNullName());
			remoteNodeAddressName.setText(node.getAddressName());
			if (node.getLastSeen() != null) {
				remoteNodeLastSeen.setText(itemView.getContext().getString(R.string.label_last_seen,
						Utils.formatTimestampByInterval(itemView.getContext(), node.getLastSeen())));
			} else {
				remoteNodeLastSeen.setText(itemView.getContext().getString(R.string.label_last_seen_never));
			}
		}
	}
}