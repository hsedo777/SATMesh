package org.sedo.satmesh.ui.adapter;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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
		holder.bind(getItem(position));
	}

	public static abstract class MessageViewHolder extends RecyclerView.ViewHolder {
		protected final TextView messageText;
		protected final TextView timestampText;
		protected final ImageView messageStatus;
		protected final LinearLayout messageContainer;

		public MessageViewHolder(@NonNull View itemView) {
			super(itemView);
			messageText = itemView.findViewById(R.id.message_text);
			timestampText = itemView.findViewById(R.id.message_time);
			messageStatus = itemView.findViewById(R.id.message_status);
			messageContainer = itemView.findViewById(R.id.message_container);
		}

		public void bind(@NonNull Message message) {
			messageText.setText(message.getContent());
			int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
			messageText.setMaxWidth((int) (0.75d * screenWidth));
			timestampText.setText(Utils.formatTimestamp(itemView.getContext(), message.getTimestamp()));
		}
	}

	public static class SentMessageViewHolder extends MessageViewHolder {
		public SentMessageViewHolder(@NonNull View itemView) {
			super(itemView);
		}

		public void bind(@NonNull Message message) {
			super.bind(message);
			@DrawableRes int drawable;
			switch (message.getStatus()) {
				case Message.MESSAGE_STATUS_DELIVERED:
					drawable = R.drawable.ic_message_status_delivered;
					break;
				case Message.MESSAGE_STATUS_PENDING:
					drawable = R.drawable.ic_message_status_pending;
					break;
				case Message.MESSAGE_STATUS_READ:
					drawable = R.drawable.ic_message_status_read;
					break;
				case Message.MESSAGE_STATUS_FAILED:
					drawable = R.drawable.ic_message_status_failed;
					break;
				case Message.MESSAGE_STATUS_ROUTING:
					drawable = R.drawable.ic_message_status_routing;
					break;
				case Message.MESSAGE_STATUS_PENDING_KEY_EXCHANGE:
					drawable = R.drawable.ic_message_status_key_exchange;
					break;
				default:
					drawable = -1;
			}
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
