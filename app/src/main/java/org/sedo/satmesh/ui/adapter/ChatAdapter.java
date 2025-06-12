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
import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.utils.Utils;

import java.util.Objects;

public class ChatAdapter extends ListAdapter<Message, ChatAdapter.MessageViewHolder> {

	private static final int VIEW_TYPE_SENT = 1;
	private static final int VIEW_TYPE_RECEIVED = 2;
	private final Long hostNodeId;

	public ChatAdapter(long hostNodeId) {
		super(new MessageDiffCallback());
		this.hostNodeId = hostNodeId;
	}

	private boolean isSentByMe(Message message){
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
		holder.bind(getItem(position));
	}

	public static abstract class MessageViewHolder extends RecyclerView.ViewHolder {
		protected TextView messageText;
		protected TextView timestampText;

		public MessageViewHolder(@NonNull View itemView) {
			super(itemView);
			messageText = itemView.findViewById(R.id.message_text);
			timestampText = itemView.findViewById(R.id.message_time);
		}

		public void bind(Message message) {
			messageText.setText(message.getContent());
			timestampText.setText(Utils.formatTimestamp(itemView.getContext(), message.getTimestamp()));
		}
	}

	public static class SentMessageViewHolder extends MessageViewHolder {

		public SentMessageViewHolder(@NonNull View itemView) {
			super(itemView);
		}
		// We will add additional behaviors
	}

	public static class ReceivedMessageViewHolder extends MessageViewHolder {

		public ReceivedMessageViewHolder(@NonNull View itemView) {
			super(itemView);
		}
		// We will add additional specialized behaviors
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
