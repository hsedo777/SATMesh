package org.sedo.satmesh.ui;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.SignalKeyExchangeState;
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.nearby.NearbySignalMessenger;
import org.sedo.satmesh.proto.PersonalInfo;
import org.sedo.satmesh.proto.TextMessage;
import org.sedo.satmesh.ui.data.MessageRepository;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeState;
import org.sedo.satmesh.ui.data.NodeTransientState;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;
import org.sedo.satmesh.ui.data.SignalKeyExchangeStateRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatViewModel extends AndroidViewModel {

	private static final String TAG = "ChatViewModel";
	private final MessageRepository messageRepository;
	private final NodeRepository nodeRepository;
	private final NearbyManager nearbyManager;
	private final NearbySignalMessenger nearbySignalMessenger;
	private final NodeTransientStateRepository nodeTransientStateRepository;
	private final SignalKeyExchangeStateRepository signalKeyExchangeStateRepository;

	private final MediatorLiveData<List<Message>> conversation = new MediatorLiveData<>();
	private final MutableLiveData<String> uiMessage = new MutableLiveData<>();

	private final MutableLiveData<Node> hostNodeLiveData = new MutableLiveData<>();
	private final MediatorLiveData<Node> remoteNodeLiveData = new MediatorLiveData<>();

	private final MutableLiveData<Boolean> connectionActive = new MutableLiveData<>();
	private final MutableLiveData<NodeState> connectionDetailedStatus = new MutableLiveData<>();

	private final MediatorLiveData<SignalKeyExchangeState> remoteKeyExchangeState = new MediatorLiveData<>();
	private LiveData<SignalKeyExchangeState> currentKeyExchangeStateSource; // Source from repository

	private final MediatorLiveData<Void> transientStatesMediator = new MediatorLiveData<>();

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	private LiveData<Node> currentRemoteNodeSource;

	public ChatViewModel(@NonNull Application application) {
		super(application);
		this.messageRepository = new MessageRepository(application);
		this.nodeRepository = new NodeRepository(application);
		this.nodeTransientStateRepository = NodeTransientStateRepository.getInstance();
		this.signalKeyExchangeStateRepository = new SignalKeyExchangeStateRepository(application);

		nearbyManager = NearbyManager.getInstance();
		nearbySignalMessenger = NearbySignalMessenger.getInstance(); // Pass application here

		setupConnectionStatusMonitoring();
	}

	private void setupConnectionStatusMonitoring() {
		// Use the explicit observer instance
		transientStatesMediator.addSource(nodeTransientStateRepository.getTransientNodeStates(), this::updateConnectionStatus);
	}

	/**
	 * Updates the connection status LiveData based on the latest states from NodeTransientStateRepository
	 * and now also from SignalKeyExchangeStateRepository.
	 *
	 * @param statesMap The current map of transient node states.
	 */
	private void updateConnectionStatus(@Nullable Map<String, NodeTransientState> statesMap) {
		Node currentRemoteNode = remoteNodeLiveData.getValue();
		if (currentRemoteNode == null || currentRemoteNode.getAddressName() == null) {
			connectionActive.postValue(false);
			connectionDetailedStatus.postValue(NodeState.ON_DISCONNECTED);
			uiMessage.postValue(getApplication().getString(R.string.status_no_remote_node_selected)); // Provide feedback
			return;
		}

		String remoteAddress = currentRemoteNode.getAddressName();

		// 1. Get Transient State
		NodeTransientState remoteTransientState = (statesMap != null) ?
				statesMap.get(remoteAddress) :
				nodeTransientStateRepository.getTransientNodeStates().getValue() != null ?
						nodeTransientStateRepository.getTransientNodeStates().getValue().get(remoteAddress) : null;

		/*
		 * 2. Get Persistent Key Exchange State (synchronously for immediate update, but it's LiveData in repo)
		 * We will observe remoteKeyExchangeState separately if more complex UI logic is needed.
		 * For this immediate update, we use the last known value of remoteKeyExchangeState.
		 */
		SignalKeyExchangeState currentPersistentKeyExchangeState = remoteKeyExchangeState.getValue();

		boolean active = false;
		NodeState detailedStatus = NodeState.ON_DISCONNECTED; // Default to disconnected
		String uiMessageText;

		if (remoteTransientState != null) {
			// Start with the general connection state from Nearby Connections
			detailedStatus = remoteTransientState.connectionState;

			// Refine status based on Signal Protocol states
			// Use the more comprehensive persistent state where possible, combined with transient.
			if (remoteTransientState.connectionState == NodeState.ON_CONNECTED) { // Only proceed if Nearby is connected
				boolean sessionReadyByTransient = (remoteTransientState.keyExchangeStatus != null && remoteTransientState.keyExchangeStatus) &&
						(remoteTransientState.sessionInitStatus != null && remoteTransientState.sessionInitStatus);

				boolean ourBundleSent = currentPersistentKeyExchangeState != null &&
						currentPersistentKeyExchangeState.getLastOurSentAttempt() != null &&
						currentPersistentKeyExchangeState.getLastOurSentAttempt() > 0L;

				boolean theirBundleReceived = currentPersistentKeyExchangeState != null &&
						currentPersistentKeyExchangeState.getLastTheirReceivedAttempt() != null &&
						currentPersistentKeyExchangeState.getLastTheirReceivedAttempt() > 0L;

				if (sessionReadyByTransient) {
					// Signal session is fully active according to transient state
					active = true;
					uiMessageText = getApplication().getString(R.string.status_secure_session_active);
				} else {
					// Nearby is connected, but Signal session is not yet fully active (transient state)
					// Not fully secure yet

					if (!ourBundleSent) {
						uiMessageText = getApplication().getString(R.string.status_waiting_to_send_our_keys);
					} else if (!theirBundleReceived) {
						uiMessageText = getApplication().getString(R.string.status_waiting_for_their_keys);
					} else {
						/*
						 * If both bundles sent/received persistence-wise, but transient says no session
						 * This might indicate an internal Signal protocol error, or a delay.
						 */
						uiMessageText = getApplication().getString(R.string.status_negotiating_secure_session_with_issue);
						detailedStatus = NodeState.ON_CONNECTION_FAILED;
					}
				}
			} else {
				// Nearby connection is NOT active (disconnected, connecting, failed, etc.)
				// UI message and detailed status already reflect this from the initial check.
				uiMessageText = getApplication().getString(R.string.status_not_connected_nearby, remoteAddress);
			}

		} else {
			// No transient state found (should not happen if `setConversationNodes` was called).
			Log.e(TAG, "Unable to find the `NodeTransientState` for address : " + remoteAddress);
			uiMessageText = getApplication().getString(R.string.error_connection_state_unknown);
		}

		if (!uiMessageText.isEmpty()) {
			uiMessage.postValue(uiMessageText);
		}

		connectionActive.postValue(active);
		connectionDetailedStatus.postValue(detailedStatus);
		Log.d(TAG, "Connection status updated for " + remoteAddress +
				": Active=" + active + ", Detailed=" + detailedStatus.name() +
				", OurBundleSent=" + (currentPersistentKeyExchangeState != null ? currentPersistentKeyExchangeState.getLastOurSentAttempt() : "N/A") +
				", TheirBundleReceived=" + (currentPersistentKeyExchangeState != null ? currentPersistentKeyExchangeState.getLastTheirReceivedAttempt() : "N/A"));
	}


	@Override
	protected void onCleared() {
		super.onCleared();
		executor.shutdown();
		if (currentRemoteNodeSource != null) {
			remoteNodeLiveData.removeSource(currentRemoteNodeSource);
		}
		if (currentKeyExchangeStateSource != null) { // NEW: Remove source for persistent key exchange state
			remoteKeyExchangeState.removeSource(currentKeyExchangeStateSource);
		}
		// Use the explicit observer instance for removal
		transientStatesMediator.removeSource(nodeTransientStateRepository.getTransientNodeStates());
		Log.d(TAG, "ChatViewModel cleared.");
	}

	public NearbyManager getNearbyManager() {
		return nearbyManager;
	}

	public LiveData<Node> getHostNodeLiveData() {
		return hostNodeLiveData;
	}

	public LiveData<Node> getRemoteNodeLiveData() {
		return remoteNodeLiveData;
	}

	public void setConversationNodes(@NonNull Node hostNode, @NonNull Node remoteNode) {
		hostNodeLiveData.postValue(hostNode);

		// Remove previous remote node and key exchange state sources if they exist
		if (currentRemoteNodeSource != null) {
			remoteNodeLiveData.removeSource(currentRemoteNodeSource);
		}
		if (currentKeyExchangeStateSource != null) { // NEW: Remove old key exchange state source
			remoteKeyExchangeState.removeSource(currentKeyExchangeStateSource);
		}

		// Setup new remote node source from NodeRepository
		LiveData<Node> newRemoteNodeSource = nodeRepository.findLiveNode(remoteNode.getId());
		currentRemoteNodeSource = newRemoteNodeSource;
		// The MediatorLiveData will observe this source and set its value.
		// We use addSource with a lambda that calls setValue to ensure it's propagated.
		// We also use this callback to trigger updateConnectionStatus when remote node changes.
		remoteNodeLiveData.addSource(newRemoteNodeSource, newNode -> {
			remoteNodeLiveData.setValue(newNode);
			// This ensures that connection status update is triggered when the remote node object itself changes in DB
			updateConnectionStatus(null);
		});

		// NEW: Setup new SignalKeyExchangeState source from its repository
		LiveData<SignalKeyExchangeState> newKeyExchangeStateSource = signalKeyExchangeStateRepository.getByRemoteAddressLiveData(remoteNode.getAddressName());
		currentKeyExchangeStateSource = newKeyExchangeStateSource;
		// Observe this source and update the remoteKeyExchangeState MediatorLiveData
		// We also use this callback to trigger updateConnectionStatus when key exchange state changes.
		remoteKeyExchangeState.addSource(newKeyExchangeStateSource, newState -> {
			remoteKeyExchangeState.setValue(newState);
			updateConnectionStatus(null); // Trigger status update when persistent key exchange state changes
		});


		// Remove old conversation source if any
		Node oldHostNode = hostNodeLiveData.getValue();
		Node oldRemoteNode = remoteNodeLiveData.getValue(); // This will still hold the OLD node if set before new remoteNodeSource
		if (oldHostNode != null && oldRemoteNode != null) {
			// Note: This needs to be removed carefully if previous source was different.
			// A safer approach might be to store the exact LiveData instance added earlier.
			// For simplicity with `remoteNodeLiveData` as source, it's fine for now.
			conversation.removeSource(messageRepository.getConversationMessages(oldHostNode.getId(), oldRemoteNode.getId()));
		}

		// Add new conversation source
		conversation.addSource(messageRepository.getConversationMessages(hostNode.getId(), remoteNode.getId()),
				conversation::setValue);

		// Initial call to update connection status.
		// This will now implicitly factor in both transient and persistent states via the observers.
		updateConnectionStatus(null);

		executor.execute(() -> {
			String endpointId = nearbyManager.getLinkedEndpointId(remoteNode.getAddressName());
			if (endpointId != null) {
				// Initiate key exchange if necessary. This method is now smart enough
				// to check for existing sessions and debounce.
				nearbySignalMessenger.handleInitialKeyExchange(endpointId, remoteNode.getAddressName());

				// Send PersonalInfo if display name is unknown/empty
				if (remoteNode.getDisplayName() == null || remoteNode.getDisplayName().isEmpty() ||
						Objects.equals(remoteNode.getDisplayName(), getApplication().getString(R.string.unknown_node))) {
					PersonalInfo info = hostNode.toPersonalInfo().toBuilder().setExpectResult(true).build();
					nearbySignalMessenger.sendPersonalInfo(info, remoteNode.getAddressName());
				}
			} else {
				// If no direct endpoint, we are not connected for Nearby Messages.
				// This state should be reflected.
				connectionActive.postValue(false);
				connectionDetailedStatus.postValue(NodeState.ON_DISCONNECTED);
				uiMessage.postValue(getApplication().getString(R.string.error_no_direct_connection, remoteNode.getDisplayName()));
			}
		});
	}

	public LiveData<List<Message>> getConversation() {
		return conversation;
	}

	public LiveData<String> getUiMessage() {
		return uiMessage;
	}

	public MutableLiveData<Boolean> getConnectionActive() {
		return connectionActive;
	}

	public MutableLiveData<NodeState> getConnectionDetailedStatus() {
		return connectionDetailedStatus;
	}

	/**
	 * Expose the LiveData for the persistent key exchange state for detailed UI feedback.
	 */
	public LiveData<SignalKeyExchangeState> getRemoteKeyExchangeState() {
		return remoteKeyExchangeState;
	}

	public boolean areNodesSet() {
		return hostNodeLiveData.getValue() != null && remoteNodeLiveData.getValue() != null;
	}

	public void sendMessage(@NonNull String content) {
		Node currentHostNode = hostNodeLiveData.getValue();
		Node currentRemoteNode = remoteNodeLiveData.getValue();

		if (currentHostNode == null || currentRemoteNode == null) {
			uiMessage.postValue(getApplication().getString(R.string.error_no_conversation_selected));
			return;
		}

		// NEW: Check for active session using `nearbySignalMessenger.hasSession` for a more direct check
		// and combine with transient and persistent states for robust check.
		boolean isSessionSecure = nearbySignalMessenger.hasSession(currentRemoteNode.getAddressName());

		if (!isSessionSecure) {
			// Also check transient and persistent states for more specific feedback to user
			NodeTransientState remoteTransientState = nodeTransientStateRepository.getTransientNodeStates().getValue() != null ?
					nodeTransientStateRepository.getTransientNodeStates().getValue().get(currentRemoteNode.getAddressName()) : null;
			SignalKeyExchangeState persistentState = remoteKeyExchangeState.getValue();

			String feedbackMessage = getApplication().getString(R.string.error_secure_session_not_active);

			if (remoteTransientState != null && remoteTransientState.connectionState != NodeState.ON_CONNECTED) {
				feedbackMessage = getApplication().getString(R.string.error_not_connected_to_send);
			} else if (persistentState == null || persistentState.getLastOurSentAttempt() == 0L) {
				feedbackMessage = getApplication().getString(R.string.error_our_keys_not_sent);
			} else if (persistentState.getLastTheirReceivedAttempt() == 0L) {
				feedbackMessage = getApplication().getString(R.string.error_waiting_for_their_keys);
			} else if (remoteTransientState != null && (!remoteTransientState.keyExchangeStatus || !remoteTransientState.sessionInitStatus)) {
				feedbackMessage = getApplication().getString(R.string.error_secure_session_negotiating); // Still negotiating, or transient issue
			}

			uiMessage.postValue(feedbackMessage);
			return; // Block message send if session is not secure
		}

		Message message = new Message();
		message.setContent(content);
		message.setTimestamp(System.currentTimeMillis());
		message.setSenderNodeId(currentHostNode.getId());
		message.setRecipientNodeId(currentRemoteNode.getId());
		message.setStatus(Message.MESSAGE_STATUS_PENDING);
		message.setType(Message.MESSAGE_TYPE_TEXT);

		messageRepository.insertMessage(message, success -> {
			if (success) {
				TextMessage text = TextMessage.newBuilder()
						.setContent(message.getContent())
						.setPayloadId(0L) // Payload ID will be set by NearbyManager on send
						.setTimestamp(message.getTimestamp())
						.build();
				nearbySignalMessenger.sendEncryptedTextMessage(currentRemoteNode.getAddressName(), text, message.getId());
			} else {
				message.setStatus(Message.MESSAGE_STATUS_FAILED);
				messageRepository.updateMessage(message);
				uiMessage.postValue(getApplication().getString(R.string.error_message_persistence_failed));
			}
		});
	}
}