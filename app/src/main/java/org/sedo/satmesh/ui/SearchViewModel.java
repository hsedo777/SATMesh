package org.sedo.satmesh.ui;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import org.sedo.satmesh.ui.data.ChatListItem;
import org.sedo.satmesh.ui.data.SearchMessageItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SearchViewModel extends ChatListViewModel {

	private static final String TAG = "SearchViewModel";

	private final MutableLiveData<String> searchQueryLiveData = new MutableLiveData<>();

	private final MediatorLiveData<List<SearchMessageItem>> searchMessageItems = new MediatorLiveData<>();
	private LiveData<List<SearchMessageItem>> currentSearchMessageItemsSource;

	public SearchViewModel(@NonNull Application application) {
		super(application);

		searchQueryLiveData.setValue("");

		searchMessageItems.addSource(hostNodeIdLiveData, hostId ->
				updateSearchSources(hostId, searchQueryLiveData.getValue())
		);
		searchMessageItems.addSource(searchQueryLiveData, query ->
				updateSearchSources(hostNodeIdLiveData.getValue(), query)
		);
	}

	/**
	 * Updates the sources for search message items and discussion items whenever
	 * the host ID or query changes. This method handles the actual database
	 * call for message search.
	 */
	private void updateSearchSources(Long hostId, String query) {
		// Remove previous source for message items if it exists
		// (This is important to prevent multiple active queries)
		if (currentSearchMessageItemsSource != null) {
			searchMessageItems.removeSource(currentSearchMessageItemsSource);
		}

		if (hostId != null && query != null) {
			// Get the new LiveData source from the repository
			currentSearchMessageItemsSource = messageRepository.searchMessagesByContentFts(query, hostId);
			// When new messages are received, update the MediatorLiveData
			searchMessageItems.addSource(currentSearchMessageItemsSource, searchMessageItems::setValue);
		} else {
			// If hostId or query is null, clear the message search results
			searchMessageItems.setValue(null);
		}

		// Update discussions display
		onHostNodeIdSet(hostId);
	}

	/**
	 * Exposes the LiveData for search results specifically for messages.
	 * This LiveData will contain SearchMessageItem objects.
	 *
	 * @return LiveData of a list of SearchMessageItem.
	 */
	public LiveData<List<SearchMessageItem>> getSearchMessageItems() {
		return searchMessageItems;
	}

	/**
	 * Sets the search query, triggering updates in both discussion and message search results.
	 *
	 * @param query The search query string from the UI.
	 */
	public void setSearchQuery(String query) {
		// Only update if the query is different to avoid unnecessary database calls
		if (!Objects.equals(query, searchQueryLiveData.getValue())) {
			searchQueryLiveData.setValue(query);
			Log.d(TAG, "Search query updated to: " + query);
		}
	}

	/**
	 * Overrides the getDiscussions method from ChatListViewModel to filter discussions
	 * based on the current search query (matching node names).
	 *
	 * @param hostNodeId The ID of the current host node.
	 * @return A LiveData list of ChatListItem objects filtered by node name.
	 */
	@Override
	protected LiveData<List<ChatListItem>> getDiscussions(long hostNodeId) {
		// Retrieve the current search query
		String currentQuery = searchQueryLiveData.getValue();
		Log.d(TAG, "Getting query string=" + currentQuery);

		if (currentQuery != null && !currentQuery.trim().isEmpty()) {
			return messageRepository.searchDiscussions(hostNodeId, currentQuery);
		} else {
			// If the search query is empty, return an empty list for discussions
			return new MutableLiveData<>(new ArrayList<>());
		}
	}
}