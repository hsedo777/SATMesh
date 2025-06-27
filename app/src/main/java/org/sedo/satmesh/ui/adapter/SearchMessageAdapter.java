package org.sedo.satmesh.ui.adapter;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.sedo.satmesh.R;
import org.sedo.satmesh.ui.data.SearchMessageItem;
import org.sedo.satmesh.utils.Utils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class SearchMessageAdapter extends ListAdapter<SearchMessageItem, SearchMessageAdapter.SearchMessageViewHolder> {

	// DiffUtil.ItemCallback for efficient list updates
	private static final DiffUtil.ItemCallback<SearchMessageItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<SearchMessageItem>() {
		@Override
		public boolean areItemsTheSame(@NonNull SearchMessageItem oldItem, @NonNull SearchMessageItem newItem) {
			return oldItem.message.getId().equals(newItem.message.getId());
		}

		@Override
		public boolean areContentsTheSame(@NonNull SearchMessageItem oldItem, @NonNull SearchMessageItem newItem) {
			return oldItem.equals(newItem);
		}
	};
	private final long hostNodeId;
	private OnItemClickListener listener;
	private String currentSearchQuery;

	public SearchMessageAdapter(long hostNodeId) {
		super(DIFF_CALLBACK);
		this.hostNodeId = hostNodeId;
	}

	/**
	 * Sets the essential context for displaying messages, including the host node
	 * and the current search query for highlighting.
	 *
	 * @param searchQuery The search query string, used to highlight matching text in messages.
	 */
	public void setSearchQuery(@NonNull String searchQuery) {
		String oldSearchQuery = currentSearchQuery;
		this.currentSearchQuery = searchQuery.toLowerCase(Locale.getDefault());
		/*
		 * Re-submit the current list to trigger DiffUtil re-evaluation with the new query.
		 * This is to solve the case where the list still same but the query String changed.
		 */
		if (!Objects.equals(oldSearchQuery, currentSearchQuery)) {
			List<SearchMessageItem> messages = getCurrentList();
			submitList(Collections.emptyList(), () -> submitList(messages));
		}
	}

	// Setter for the item click listener
	public void setOnItemClickListener(OnItemClickListener listener) {
		this.listener = listener;
	}

	private void dispatchItemClick(int position) {
		SearchMessageItem item = getItem(position);
		if (item != null && listener != null) {
			listener.onItemClick(item);
		}
	}

	@NonNull
	@Override
	public SearchMessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View itemView = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_search_message, parent, false); // Uses the item_search_message.xml layout
		return new SearchMessageViewHolder(itemView, this::dispatchItemClick);
	}

	@Override
	public void onBindViewHolder(@NonNull SearchMessageViewHolder holder, int position) {
		SearchMessageItem currentItem = getItem(position);
		holder.bind(currentItem, hostNodeId, currentSearchQuery);
	}

	public interface OnItemClickListener {
		void onItemClick(@NonNull SearchMessageItem item);
	}

	// Helper interface for ViewHolder internal click dispatching
	public interface OnItemClickCallback {
		void onItemClick(int position);
	}

	/**
	 * ViewHolder for individual search message items.
	 * Binds SearchMessageItem data to the views defined in item_search_message.xml.
	 */
	public static class SearchMessageViewHolder extends RecyclerView.ViewHolder {
		private final TextView remoteNodeDisplayName;
		private final TextView messageDate;
		private final TextView messagePreview;

		public SearchMessageViewHolder(@NonNull View itemView, @NonNull OnItemClickCallback callback) {
			super(itemView);
			remoteNodeDisplayName = itemView.findViewById(R.id.text_remote_node_name);
			messageDate = itemView.findViewById(R.id.text_message_date);
			messagePreview = itemView.findViewById(R.id.text_message_preview);

			itemView.setOnClickListener(v -> {
				if (getAdapterPosition() != RecyclerView.NO_POSITION) {
					callback.onItemClick(getAdapterPosition());
				}
			});
		}

		/**
		 * Binds a SearchMessageItem object to the views in the ViewHolder, with highlighting.
		 *
		 * @param item        The SearchMessageItem object to bind (contains Message and remoteNode).
		 * @param hostNodeId  The local ID of the host node, to determine if sender is "Me".
		 * @param searchQuery The search query to highlight in the message content.
		 */
		public void bind(@NonNull SearchMessageItem item, long hostNodeId, String searchQuery) {
			// 1. Set Remote Node Name
			String senderLabel;
			Long hostId = hostNodeId;
			if (hostId.equals(item.message.getSenderNodeId())) {
				senderLabel = itemView.getContext().getString(R.string.you);
			} else {
				// If it's not the host, it must be the remoteNode associated with the message
				senderLabel = item.remoteNode.getNonNullName();
			}
			remoteNodeDisplayName.setText(senderLabel);

			// 2. Set Message Date
			messageDate.setText(Utils.formatTimestamp(itemView.getContext(), item.message.getTimestamp()));

			// 3. Set Message Preview with Highlighting
			String messageContent = item.message.getContent();
			// Normalize content and query for accent-insensitive and case-insensitive search
			String normalizedContent = java.text.Normalizer.normalize(messageContent, java.text.Normalizer.Form.NFD)
					.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
					.toLowerCase(Locale.getDefault());

			String normalizedSearchQuery = java.text.Normalizer.normalize(searchQuery, java.text.Normalizer.Form.NFD)
					.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
					.toLowerCase(Locale.getDefault());

			// Only proceed with highlighting if the normalized query is not empty
			if (!normalizedSearchQuery.isEmpty()) {
				SpannableString spannableString = new SpannableString(messageContent); // Use original content for SpannableString

				int lastIndex = 0;
				while (lastIndex != -1) {
					// Find the index in the normalized content
					lastIndex = normalizedContent.indexOf(normalizedSearchQuery, lastIndex);

					if (lastIndex != -1) {
						// Apply the span to the ORIGINAL content using the found index
						spannableString.setSpan(new StyleSpan(Typeface.BOLD),
								lastIndex, lastIndex + normalizedSearchQuery.length(),
								Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
						lastIndex += normalizedSearchQuery.length();
					}
				}
				messagePreview.setText(spannableString);
			} else {
				// If search query is empty, just set the original message content without highlighting
				messagePreview.setText(messageContent);
			}
		}
	}
}