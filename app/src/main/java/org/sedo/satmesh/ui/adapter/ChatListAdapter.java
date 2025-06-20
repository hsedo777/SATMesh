package org.sedo.satmesh.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.sedo.satmesh.R;
import org.sedo.satmesh.ui.data.ChatListItem;
import org.sedo.satmesh.utils.Utils;

public class ChatListAdapter extends ListAdapter<ChatListItem, ChatListAdapter.ChatListItemViewHolder> {

	// DiffUtil.ItemCallback for efficient list updates
	private static final DiffUtil.ItemCallback<ChatListItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<ChatListItem>() {
		@Override
		public boolean areItemsTheSame(@NonNull ChatListItem oldItem, @NonNull ChatListItem newItem) {
			// Items are the same if they represent the same conversation.
			return oldItem.remoteNode.getId().equals(newItem.remoteNode.getId());
		}

		@Override
		public boolean areContentsTheSame(@NonNull ChatListItem oldItem, @NonNull ChatListItem newItem) {
			// Contents are the same if all relevant fields are equal.
			// This is crucial for precise UI updates.
			return oldItem.equals(newItem);
		}
	};
	private final long hostNodeId;
	// Listener for item clicks
	private OnItemClickListener listener;

	public ChatListAdapter(long hostNodeId) {
		super(DIFF_CALLBACK);
		this.hostNodeId = hostNodeId;
	}

	// Setter for the click listener
	public void setOnItemClickListener(OnItemClickListener listener) {
		this.listener = listener;
	}

	private void dispatchItemClick(int position) {
		ChatListItem item = getItem(position);
		if (item != null && listener != null) {
			listener.onItemClick(item);
		}
	}

	@NonNull
	@Override
	public ChatListItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View itemView = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_conversation, parent, false);
		return new ChatListItemViewHolder(itemView, this::dispatchItemClick);
	}

	@Override
	public void onBindViewHolder(@NonNull ChatListItemViewHolder holder, int position) {
		ChatListItem currentItem = getItem(position);
		holder.bind(currentItem, hostNodeId);
	}

	public interface OnItemClickListener {
		void onItemClick(@NonNull ChatListItem item);
	}

	public interface OnItemClickCallback {
		void onItemClick(int position);
	}

	/**
	 * ViewHolder for individual chat list items.
	 * Binds ChatListItem data to the views defined in item_conversation.xml.
	 */
	public static class ChatListItemViewHolder extends RecyclerView.ViewHolder {
		private final TextView remoteNodeDisplayName;
		private final TextView lastMessageContent;
		private final TextView lastMessageTimestamp;
		private final TextView unreadCountBadge;
		private final View connectivityStatus;

		public ChatListItemViewHolder(@NonNull View itemView, @NonNull OnItemClickCallback callback) {
			super(itemView);
			remoteNodeDisplayName = itemView.findViewById(R.id.tvRemoteNodeName);
			lastMessageContent = itemView.findViewById(R.id.tvLastMessageContent);
			lastMessageTimestamp = itemView.findViewById(R.id.tvLastMessageTime);
			connectivityStatus = itemView.findViewById(R.id.tvConnectivityStatus);
			unreadCountBadge = itemView.findViewById(R.id.tvUnreadCountBadge);

			// Set click listener for the whole item
			itemView.setOnClickListener(v -> {
				if (getAdapterPosition() != RecyclerView.NO_POSITION) {
					callback.onItemClick(getAdapterPosition());
				}
			});
		}

		/**
		 * Binds a ChatListItem object to the views in the ViewHolder.
		 *
		 * @param item The ChatListItem object to bind.
		 */
		public void bind(ChatListItem item, long ignored) {
			remoteNodeDisplayName.setText(item.remoteNode.getNonNullName());
			lastMessageContent.setText(item.lastMessage.getContent());
			lastMessageTimestamp.setText(Utils.formatTimestamp(itemView.getContext(), item.lastMessage.getTimestamp()));
			// Update connectivity status icon based on nodeState
			if (item.nodeState != null) {
				connectivityStatus.getBackground().setTint(ContextCompat.getColor(itemView.getContext(), item.nodeState.getColorResId()));
				connectivityStatus.setVisibility(View.VISIBLE);
			} else {
				connectivityStatus.setVisibility(View.GONE);
			}
			unreadCountBadge.setText(String.valueOf(item.unreadCount));
			unreadCountBadge.setVisibility(item.unreadCount != 0 ? View.VISIBLE : View.GONE);
		}
	}
}