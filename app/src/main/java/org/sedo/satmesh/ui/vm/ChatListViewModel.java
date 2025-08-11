package org.sedo.satmesh.ui.vm;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import org.sedo.satmesh.ui.data.ChatListItem;
import org.sedo.satmesh.ui.data.MessageRepository;
import org.sedo.satmesh.ui.data.NodeTransientState;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ChatListViewModel extends AndroidViewModel {

	private final static String TAG = "ChatListViewModel";
	// Repositories instances created or obtained within the ViewModel
	protected final MessageRepository messageRepository;

	// MutableLiveData to hold the hostNodeId, which will trigger data loading
	protected final MutableLiveData<Long> hostNodeIdLiveData = new MutableLiveData<>();

	// MediatorLiveData to combine chat list items with node connectivity states
	protected final MediatorLiveData<List<ChatListItem>> chatListItems = new MediatorLiveData<>();
	// LiveData for node transient states from the nodeTransientStateRepository (which is a Map)
	private final LiveData<Map<String, NodeTransientState>> currentNodeTransientStatesSource;
	// LiveData for the raw chat list items from the messageRepository (before enrichment)
	private LiveData<List<ChatListItem>> currentChatListItemsSource;

	public ChatListViewModel(@NonNull Application application) {
		super(application);

		this.messageRepository = new MessageRepository(application);
		currentNodeTransientStatesSource = NodeTransientStateRepository.getInstance().getTransientNodeStates();

		// Setup the MediatorLiveData to combine and enrich the data
		chatListItems.addSource(hostNodeIdLiveData, this::onHostNodeIdSet);
		// Initialize NodeTransientStates source only once
		chatListItems.addSource(currentNodeTransientStatesSource, nodeStatesMap ->
				combineLatestData(currentChatListItemsSource != null ? currentChatListItemsSource.getValue() : null, nodeStatesMap)
		);
	}

	protected void onHostNodeIdSet(Long hostNodeId) {
		if (hostNodeId != null) {
			// If hostNodeId changes or is set for the first time
			// Remove previous source for chat items if it exists
			if (currentChatListItemsSource != null) {
				chatListItems.removeSource(currentChatListItemsSource);
			}
			Log.d(TAG, "Start loading items at: " + System.currentTimeMillis());
			currentChatListItemsSource = getDiscussions(hostNodeId);
			chatListItems.addSource(currentChatListItemsSource, chatItems -> {
				Log.d(TAG, "Loading items ends at: " + System.currentTimeMillis());
				combineLatestData(chatItems, currentNodeTransientStatesSource.getValue());
			});
		} else {
			// If hostNodeId becomes null, clear the chat list
			chatListItems.setValue(null);
		}
	}

	public LiveData<List<ChatListItem>> getChatListItems() {
		return chatListItems;
	}

	public Long getHostNodeId() {
		return hostNodeIdLiveData.getValue();
	}

	/**
	 * REQUIREMENT 3: Initializes the host node ID.
	 * This method will trigger the loading and observation of chat list items.
	 *
	 * @param id The ID of the current host node.
	 */
	public void setHostNodeIdLiveData(@NonNull Long id) {
		// Only update if the ID is different or being set from null to a value
		if (!Objects.equals(id, hostNodeIdLiveData.getValue())) {
			hostNodeIdLiveData.setValue(id);
		}
	}

	/**
	 * Get the {@code LiveData} of discussions
	 *
	 * @param hostNodeId the local ID of the host node.
	 */
	protected LiveData<List<ChatListItem>> getDiscussions(long hostNodeId) {
		return messageRepository.getChatListItems(hostNodeId);
	}

	/**
	 * Combines the latest chat items with the latest node connectivity states
	 * to produce an enriched list of ChatListItem for the UI.
	 * REQUIREMENT 5: Integrates connectivity status.
	 *
	 * @param chatItems     The list of ChatListItem from the MessageRepository.
	 * @param nodeStatesMap The map of NodeTransientState from the NodeTransientStateRepository.
	 */
	private void combineLatestData(List<ChatListItem> chatItems, Map<String, NodeTransientState> nodeStatesMap) {
		if (chatItems == null) {
			chatListItems.setValue(null);
			return;
		}

		// Ensure the nodeStatesMap is not null for lookup, default to empty map if null
		Map<String, NodeTransientState> safeNodeStatesMap = (nodeStatesMap != null) ? nodeStatesMap : new java.util.HashMap<>();

		// Create a new list with enriched ChatListItem objects
		List<ChatListItem> enrichedChatItems = chatItems.stream()
				.peek(item -> {
					// Use Node's addressName to lookup in the map
					NodeTransientState transientState = safeNodeStatesMap.get(item.remoteNode.getAddressName());
					if (transientState != null) {
						item.nodeState = transientState.connectionState;
					}
				})
				.collect(Collectors.toList());

		chatListItems.setValue(enrichedChatItems);
	}

	/**
	 * Retrieves the oldest unread message ID. This method calls blocking operations on the database.
	 * Caller must ensure using a background thread.
	 *
	 * @param item The ChatListItem for which to find the oldest unread message ID.
	 * @return The ID of the oldest unread message, or null if no unread messages are found.
	 */
	public Long findOldestUnreadMessageIdSync(ChatListItem item) {
		return messageRepository.findOldestUnreadMessageIdSync(getHostNodeId(), item.remoteNode.getId());
	}
}