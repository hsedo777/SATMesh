package org.sedo.satmesh.ui.adapter;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.ui.UiUtils;
import org.sedo.satmesh.utils.Utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ChatAdapter extends ListAdapter<Message, ChatAdapter.MessageViewHolder> {

	private static final int VIEW_TYPE_SENT = 1;
	private static final int VIEW_TYPE_RECEIVED = 2;
	private final Long hostNodeId;

	private final Set<Long> selectedMessageIds;
	// Listener for long clicks on messages
	private OnMessageLongClickListener longClickListener;
	// Listener for regular clicks on messages when in selection mode
	private OnMessageClickListener clickListener;

	public ChatAdapter(long hostNodeId) {
		super(new MessageDiffCallback());
		this.hostNodeId = hostNodeId;
		selectedMessageIds = new HashSet<>();
	}

	@Override
	public Message getItem(int position) {
		return super.getItem(position);
	}

	private boolean isSentByMe(Message message) {
		return message != null && hostNodeId.equals(message.getSenderNodeId());
	}

	@Override
	public int getItemViewType(int position) {
		Message message = getItem(position);
		return isSentByMe(message) ? VIEW_TYPE_SENT : VIEW_TYPE_RECEIVED;
	}

	@NonNull
	@Override
	public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == VIEW_TYPE_SENT) {
			return new SentMessageViewHolder(inflater.inflate(R.layout.item_message_sent, parent, false));
		} else {
			return new ReceivedMessageViewHolder(inflater.inflate(R.layout.item_message_received, parent, false));
		}
	}

	@Override
	public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
		Message message = getItem(position);
		holder.bind(message);

		// Update ViewHolder's internal state and UI
		holder.setSelected(selectedMessageIds.contains(message.getId()));

		// Set up click listeners for selection
		holder.itemView.setOnLongClickListener(v -> {
			if (longClickListener != null) {
				// Pass the message and the ViewHolder for direct interaction
				longClickListener.onMessageLongClick(message, holder);
				return true; // Consume the long click event
			}
			return false;
		});

		holder.itemView.setOnClickListener(v -> {
			// Only handle clicks for selection if there are messages already selected
			if (!selectedMessageIds.isEmpty() && clickListener != null) {
				clickListener.onMessageClick(message, holder);
			}
			// If not in selection mode (selectedMessagePositions is empty),
		});
	}

	public void setOnMessageLongClickListener(OnMessageLongClickListener listener) {
		this.longClickListener = listener;
	}

	public void setOnMessageClickListener(OnMessageClickListener listener) {
		this.clickListener = listener;
	}

	/**
	 * Toggles the selection state of a given message.
	 *
	 * @param id The id of the message to toggle.
	 */
	public void toggleSelection(@NonNull Long id) {
		boolean wasSelected = selectedMessageIds.contains(id);
		if (wasSelected) {
			selectedMessageIds.remove(id);
		} else {
			selectedMessageIds.add(id);
		}
		// Notify the adapter that this item changed to trigger re-binding and update its visual selection state.
		Message message = getCurrentList().stream().filter(m -> id.equals(m.getId())).findFirst().orElse(null);
		int position = message != null ? getCurrentList().indexOf(message) : -1;
		if (position != -1)
			notifyItemChanged(position);
	}

	/**
	 * Clears all selected messages.
	 * Notifies adapter for all affected items.
	 */
	public void clearSelection() {
		if (!selectedMessageIds.isEmpty()) {
			Set<Long> oldSelectedPositions = new HashSet<>(selectedMessageIds);
			selectedMessageIds.clear();
			List<Message> messages = getCurrentList();
			List<Message> selectedMessages = messages.stream().filter(message -> oldSelectedPositions.contains(message.getId())).collect(Collectors.toList());
			// Invalidate all previously selected items to update their UI
			for (Message message : selectedMessages) {
				int position = messages.indexOf(message);
				if (position != -1) {
					notifyItemChanged(position);
				}
			}
		}
	}

	/**
	 * Checks if any messages are currently selected.
	 *
	 * @return true if at least one message is selected, false otherwise.
	 */
	public boolean hasSelection() {
		return !selectedMessageIds.isEmpty();
	}

	/**
	 * Returns the number of currently selected messages.
	 *
	 * @return The count of selected messages.
	 */
	public int getSelectedCount() {
		return selectedMessageIds.size();
	}

	/**
	 * Returns a set of IDs of all currently selected messages.
	 *
	 * @return A {@link Set} of message IDs.
	 */
	@NonNull
	public Set<Long> getSelectedMessageIds() {
		return Collections.unmodifiableSet(selectedMessageIds);
	}

	@Nullable
	public Message getMessageById(Long messageId) {
		return getCurrentList().stream()
				.filter(m -> m.getId().equals(messageId))
				.findFirst().orElse(null);
	}

	public interface OnMessageLongClickListener {
		void onMessageLongClick(@NonNull Message message, @NonNull MessageViewHolder holder);
	}

	public interface OnMessageClickListener {
		void onMessageClick(@NonNull Message message, @NonNull MessageViewHolder holder);
	}

	public static abstract class MessageViewHolder extends RecyclerView.ViewHolder {
		protected final TextView messageText;
		protected final TextView timestampText;
		protected final ImageView messageStatus;

		public MessageViewHolder(@NonNull View itemView) {
			super(itemView);
			messageText = itemView.findViewById(R.id.message_text);
			timestampText = itemView.findViewById(R.id.message_time);
			messageStatus = itemView.findViewById(R.id.message_status);
		}

		public void bind(@NonNull Message message) {
			messageText.setText(message.getContent());
			int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
			messageText.setMaxWidth((int) (0.75d * screenWidth));
			timestampText.setText(Utils.formatTimestampByInterval(itemView.getContext(), message.getTimestamp()));
		}

		public void setSelected(boolean selected) {
			itemView.setSelected(selected);
		}
	}

	public static class SentMessageViewHolder extends MessageViewHolder {
		public SentMessageViewHolder(@NonNull View itemView) {
			super(itemView);
		}

		public void bind(@NonNull Message message) {
			super.bind(message);
			int drawable = UiUtils.getMessageStatusIcon(message.getStatus());
			if (drawable != -1) {
				messageStatus.setImageDrawable(ContextCompat.getDrawable(itemView.getContext(), drawable));
				messageStatus.setVisibility(View.VISIBLE);
			} else {
				messageStatus.setVisibility(View.GONE);
			}
		}
	}

	public static class ReceivedMessageViewHolder extends MessageViewHolder {

		public ReceivedMessageViewHolder(@NonNull View itemView) {
			super(itemView);
		}

		public void bind(@NonNull Message message) {
			super.bind(message);
			messageStatus.setVisibility(View.GONE);
		}
	}

	public static class MessageDiffCallback extends DiffUtil.ItemCallback<Message> {

		@Override
		public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
			return Objects.equals(oldItem.getId(), newItem.getId());
		}

		@Override
		public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
			return oldItem.equals(newItem);
		}
	}
}
