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

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

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
	private String currentOriginalQuery;
	private String currentNormalizedQuery;

	public SearchMessageAdapter(long hostNodeId) {
		super(DIFF_CALLBACK);
		this.hostNodeId = hostNodeId;
	}

	/**
	 * Updates the list of search message items displayed by the adapter and
	 * sets the current search query for text highlighting.
	 * The provided {@code searchQuery} is internally normalized and lowercased once
	 * to optimize highlighting performance.
	 *
	 * <p>This method utilizes {@link DiffUtil} to efficiently calculate and dispatch
	 * updates to the RecyclerView, minimizing UI changes. The {@code onComplete}
	 * {@link Runnable} is executed once {@link DiffUtil} has finished processing the list
	 * and all UI updates have been dispatched, ensuring that dependent UI logic (like
	 * visibility of "no results" messages) is performed on a fully updated RecyclerView.</p>
	 *
	 * @param messages    The new list of {@link SearchMessageItem} objects to be displayed.
	 *                    This list will be diffed against the currently displayed list.
	 * @param searchQuery The original search query string provided by the user. This string
	 *                    is used for calculating highlight positions and displaying the exact
	 *                    matched text. It will be normalized and lowercased internally.
	 * @param onComplete  A {@link Runnable} to be executed after the new list has been
	 *                    processed by {@link DiffUtil} and all necessary UI updates have been
	 *                    dispatched to the RecyclerView. This is ideal for updating view
	 *                    visibilities or other logic that depends on the final state of the list.
	 */
	public void setSearchContent(List<SearchMessageItem> messages, @NonNull String searchQuery, @NonNull Runnable onComplete) {
		this.currentOriginalQuery = searchQuery.trim();
		this.currentNormalizedQuery = Normalizer.normalize(searchQuery.trim(), Normalizer.Form.NFD)
				.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
				.toLowerCase(Locale.getDefault());
		submitList(messages, onComplete);
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
		holder.bind(currentItem, hostNodeId, currentOriginalQuery, currentNormalizedQuery);
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
		 * @param item                  The SearchMessageItem object to bind.
		 * @param hostNodeId            The local ID of the host node.
		 * @param originalSearchQuery   The original search query string (e.g., "café").
		 * @param normalizedSearchQuery The pre-normalized and lowercased search query for efficient matching (e.g., "cafe").
		 */
		public void bind(@NonNull SearchMessageItem item, long hostNodeId, String originalSearchQuery, String normalizedSearchQuery) {
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

			// Only proceed with highlighting if the normalized query is not empty
			if (!normalizedSearchQuery.isEmpty()) {
				// Normalize content and query for accent-insensitive and case-insensitive search
				String normalizedContent = java.text.Normalizer.normalize(messageContent, java.text.Normalizer.Form.NFD)
						.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
						.toLowerCase(Locale.getDefault());

				SpannableString spannableString = new SpannableString(messageContent); // Use original content for SpannableString

				int lastIndex = 0;
				while (lastIndex != -1) {
					// Find the index in the normalized content
					lastIndex = normalizedContent.indexOf(normalizedSearchQuery, lastIndex);

					if (lastIndex != -1) {
						// Apply the span to the ORIGINAL content using the found index
						spannableString.setSpan(new StyleSpan(Typeface.BOLD),
								lastIndex, lastIndex + originalSearchQuery.length(),
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