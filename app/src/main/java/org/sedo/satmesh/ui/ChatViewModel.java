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
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.nearby.NearbySignalMessenger;
import org.sedo.satmesh.proto.PersonalInfo;
import org.sedo.satmesh.proto.TextMessage;
import org.sedo.satmesh.ui.data.MessageRepository;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeState;
import org.sedo.satmesh.ui.data.NodeTransientState;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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

	private final MediatorLiveData<List<Message>> conversation = new MediatorLiveData<>();
	private final MutableLiveData<String> uiMessage = new MutableLiveData<>();

	private final MutableLiveData<Node> hostNodeLiveData = new MutableLiveData<>();
	private final MediatorLiveData<Node> remoteNodeLiveData = new MediatorLiveData<>();

	private final MutableLiveData<Boolean> connectionActive = new MutableLiveData<>();
	private final MutableLiveData<NodeState> connectionDetailedStatus = new MutableLiveData<>();

	private final NodeTransientStateRepository transientStateRepository;
	// MediatorLiveData to trigger message resend logic
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	// Flag to prevent multiple resend attempts per session activation
	private boolean hasAttemptedResendForCurrentSession = false;
	private LiveData<Node> currentRemoteNodeSource;

	public ChatViewModel(@NonNull Application application) {
		super(application);
		this.messageRepository = new MessageRepository(application);
		this.nodeRepository = new NodeRepository(application);

		nearbyManager = NearbyManager.getInstance();
		nearbySignalMessenger = NearbySignalMessenger.getInstance(); // Pass application here
		transientStateRepository = NodeTransientStateRepository.getInstance();
	}

	/**
	 * Evaluates conditions for attempting to resend messages and triggers the resend function.
	 * This method is called by the sources of `canAttemptResend` MediatorLiveData.
	 */
	private void evaluateAndTriggerResend(Boolean isActive) {
		executor.execute(() -> {
			Node remoteNode = remoteNodeLiveData.getValue();

			// Check if remote node is set, connection is active/secure, and we haven't attempted resend for this session yet
			if (remoteNode != null && remoteNode.getAddressName() != null &&
					isActive != null && isActive && !hasAttemptedResendForCurrentSession) {

				// Perform final Signal session check directly before triggering
				if (nearbySignalMessenger.hasSession(remoteNode.getAddressName())) {
					Log.d(TAG, "Conditions met for resending failed messages.");
					attemptResendFailedMessages();
					hasAttemptedResendForCurrentSession = true; // Mark as attempted for this session
				}
			} else if (isActive != null && !isActive) {
				// Reset the flag if connection goes inactive, so it can retry on next activation
				hasAttemptedResendForCurrentSession = false;
			}
		});
	}

	/**
	 * Updates the connection status LiveData based on the latest states from NodeTransientStateRepository
	 * and now also from SignalKeyExchangeStateRepository.
	 *
	 * @param statesMap The current map of transient node states.
	 */
	private void updateConnectionStatus(@Nullable Map<String, NodeTransientState> statesMap) {
		executor.execute(() -> {
			Node currentRemoteNode = remoteNodeLiveData.getValue();
			if (currentRemoteNode == null || currentRemoteNode.getAddressName() == null) {
				Log.d(TAG, "updateConnectionStatus() : Reading `null` as remote node.");
				return;
			}

			String remoteAddress = currentRemoteNode.getAddressName();

			// 1. Get Transient State
			Map<String, NodeTransientState> safeMap;
			if (statesMap != null) {
				safeMap = statesMap;
			} else if (transientStateRepository.getTransientNodeStates().getValue() != null) {
				safeMap = transientStateRepository.getTransientNodeStates().getValue();
			} else {
				safeMap = new HashMap<>();
			}
			NodeTransientState remoteTransientState = safeMap.get(remoteAddress);

			boolean active = false;
			NodeState detailedStatus; // Default to disconnected
			String uiMessageText;

			if (remoteTransientState != null) {
				// Start with the general connection state from Nearby Connections
				detailedStatus = remoteTransientState.connectionState;

				// Refine status based on Signal Protocol states
				// Use the more comprehensive persistent state where possible, combined with transient.
				if (remoteTransientState.connectionState == NodeState.ON_CONNECTED) { // Only proceed if Nearby is connected
					/*
					 * Determine if Signal session is fully active based on `nearbySignalMessenger.hasSession()`
					 * and transient states for more specific feedback.
					 */
					boolean isSignalSessionTrulySecure = nearbySignalMessenger.hasSession(remoteAddress);

					if (isSignalSessionTrulySecure) {
						// Signal session is fully active according to transient state
						active = true;
						uiMessageText = getApplication().getString(R.string.status_secure_session_active);
					} else {
						/*
						 * Nearby is connected, but Signal session is not yet fully active (transient state)
						 * This might indicate an internal Signal protocol error, or a delay.
						 */
						uiMessageText = getApplication().getString(R.string.status_negotiating_secure_session_with_issue);
					}
				} else {
					/*
					 * Nearby connection is NOT active (disconnected, connecting, failed, etc.)
					 * UI message and detailed status already reflect this from the initial check.
					 */
					uiMessageText = getApplication().getString(R.string.status_not_connected_nearby);
				}
			} else {
				// No transient state found (should not happen if `setConversationNodes` was called).
				Log.e(TAG, "Unable to find the `NodeTransientState` for address : " + remoteAddress);
				detailedStatus = null;
				uiMessageText = getApplication().getString(R.string.error_connection_state_unknown);
			}

			if (!uiMessageText.isEmpty()) {
				uiMessage.postValue(uiMessageText);
			}

			connectionActive.postValue(active);
			connectionDetailedStatus.postValue(detailedStatus);
			Log.d(TAG, "Connection status updated for " + remoteAddress +
					": Active=" + active + ", Detailed=" + detailedStatus);
		});
	}

	private void clearSource() {
		if (currentRemoteNodeSource != null) {
			remoteNodeLiveData.removeSource(currentRemoteNodeSource);
		}
		// Remove sources for connection active
		conversation.removeSource(connectionActive);

		// Use the explicit observer instance for removal
		conversation.removeSource(transientStateRepository.getTransientNodeStates());
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		executor.shutdown();

		clearSource();
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

	/**
	 * Sets the host and remote nodes for the conversation. This also triggers
	 * the initial key exchange process and loads messages for the conversation.
	 *
	 * @param hostNode   The local node.
	 * @param remoteNode The remote node for the conversation.
	 */
	public void setConversationNodes(@NonNull Node hostNode, @NonNull Node remoteNode) {
		// Remove previous remote node and key exchange state sources if they exist
		clearSource();

		// Remove old conversation source if any
		Node oldHostNode = hostNodeLiveData.getValue();
		Node oldRemoteNode = remoteNodeLiveData.getValue(); // This will still hold the OLD node if set before new remoteNodeSource
		if (oldHostNode != null && oldRemoteNode != null) {
			/*
			 * Note: This needs to be removed carefully if previous source was different.
			 * A safer approach might be to store the exact LiveData instance added earlier.
			 * For simplicity with `remoteNodeLiveData` as source, it's fine for now.
			 */
			conversation.removeSource(messageRepository.getConversationMessages(oldHostNode.getId(), oldRemoteNode.getId()));
		}

		// Add new conversation source
		conversation.addSource(messageRepository.getConversationMessages(hostNode.getId(), remoteNode.getId()),
				conversation::setValue);
		hostNodeLiveData.setValue(hostNode);
		remoteNodeLiveData.setValue(remoteNode); // Sets to start handling connection status first
		conversation.addSource(transientStateRepository.getTransientNodeStates(), this::updateConnectionStatus);

		// Reset the resend flag for the new conversation session
		hasAttemptedResendForCurrentSession = false;
		// Setup the resend trigger
		// Observe the overall connection active status
		// Only trigger if connection is active and remoteNode is set
		conversation.addSource(connectionActive, this::evaluateAndTriggerResend);

		// Setup new remote node source from NodeRepository
		LiveData<Node> newRemoteNodeSource = nodeRepository.findLiveNode(remoteNode.getId());
		currentRemoteNodeSource = newRemoteNodeSource;
		/*
		 * The MediatorLiveData will observe this source and set its value.
		 * We use addSource with a lambda that calls setValue to ensure it's propagated.
		 */
		remoteNodeLiveData.addSource(newRemoteNodeSource, remoteNodeLiveData::setValue);

		executor.execute(() -> {
			String endpointId = nearbyManager.getLinkedEndpointId(remoteNode.getAddressName());
			if (endpointId != null) {
				/*
				 * Initiate key exchange if necessary. This method is now smart enough
				 * to check for existing sessions and debounce.
				 */
				nearbySignalMessenger.handleInitialKeyExchange(endpointId, remoteNode.getAddressName());

				// Send PersonalInfo if display name is unknown/empty
				if (remoteNode.getDisplayName() == null || remoteNode.getDisplayName().isEmpty()) {
					PersonalInfo info = hostNode.toPersonalInfo(true);
					nearbySignalMessenger.sendPersonalInfo(info, remoteNode.getAddressName());
				}
			} else {
				// If no direct endpoint, we are not connected for Nearby Messages.
				// This state should be reflected.
				Log.d(TAG, "The remote node isn't directly connected to host node.");
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
	 * Sends a chat message. If a secure session is not active, the message will be
	 * saved with MESSAGE_STATUS_FAILED and attempted to be resent later.
	 *
	 * @param content The text content of the message.
	 */
	public void sendMessage(@NonNull String content) {
		Node currentHostNode = hostNodeLiveData.getValue();
		Node currentRemoteNode = remoteNodeLiveData.getValue();

		if (currentHostNode == null || currentRemoteNode == null) {
			uiMessage.postValue(getApplication().getString(R.string.error_no_conversation_selected));
			return;
		}

		executor.execute(() -> {
			Message message = new Message();
			message.setContent(content);
			message.setTimestamp(System.currentTimeMillis());
			message.setSenderNodeId(currentHostNode.getId());
			message.setRecipientNodeId(currentRemoteNode.getId());
			message.setType(Message.MESSAGE_TYPE_TEXT);

			/*
			 * Check for active session using `nearbySignalMessenger.hasSession` for a more direct check
			 * and combine with transient and persistent states for robust check.
			 */
			boolean isSessionSecure = nearbySignalMessenger.hasSession(currentRemoteNode.getAddressName());
			message.setStatus(isSessionSecure ? Message.MESSAGE_STATUS_PENDING : Message.MESSAGE_STATUS_FAILED);

			messageRepository.insertMessage(message, success -> {
				if (success) {
					TextMessage text = TextMessage.newBuilder()
							.setContent(message.getContent())
							.setPayloadId(0L) // Payload ID will be set by NearbySignalManager on send
							.setTimestamp(message.getTimestamp())
							.build();
					nearbySignalMessenger.sendEncryptedTextMessage(currentRemoteNode.getAddressName(), text, message.getId());
				} else {
					message.setStatus(Message.MESSAGE_STATUS_FAILED);
					messageRepository.updateMessage(message);
					uiMessage.postValue(getApplication().getString(R.string.error_message_persistence_failed));
				}
			});
		});
	}

	/**
	 * Attempts to resend messages that previously failed to send to the current
	 * remote node and tries to send delivery ack for messages the previous attempt failed.
	 * This method is triggered when a secure session is established.
	 */
	private void attemptResendFailedMessages() {
		Node currentRemoteNode = remoteNodeLiveData.getValue();
		if (currentRemoteNode == null || currentRemoteNode.getAddressName() == null) {
			Log.w(TAG, "attemptResendFailedMessages: Remote node not set, cannot resend messages.");
			return;
		}

		executor.execute(() -> {
			// Retrieve messages with MESSAGE_STATUS_FAILED or MESSAGE_STATUS_PENDING for the current remote node
			List<Message> failedMessages = messageRepository.getMessagesInStatusesForRecipientSync(
					currentRemoteNode.getId(),
					Arrays.asList(Message.MESSAGE_STATUS_FAILED,
							Message.MESSAGE_STATUS_PENDING /* Occurs when the send failure mapping failed*/
					));

			if (failedMessages != null && !failedMessages.isEmpty()) {
				Log.d(TAG, "Found " + failedMessages.size() + " failed messages to resend for " + currentRemoteNode.getDisplayName());
				for (Message message : failedMessages) {
					// Before attempting to resend, update its status to PENDING
					message.setStatus(Message.MESSAGE_STATUS_PENDING);
					messageRepository.updateMessage(message); // This will update UI if it observes

					TextMessage text = TextMessage.newBuilder()
							.setContent(message.getContent())
							.setPayloadId(Objects.requireNonNullElse(message.getPayloadId(), 0L))
							.setTimestamp(message.getTimestamp())
							.build();

					// Send the message. nearbySignalMessenger will handle success/failure status update.
					nearbySignalMessenger.sendEncryptedTextMessage(currentRemoteNode.getAddressName(), text, message.getId());
				}
				uiMessage.postValue(getApplication().getString(R.string.resending_failed_messages));
			} else {
				Log.d(TAG, "No failed messages found to resend for " + currentRemoteNode.getDisplayName());
			}

			// Retrieve message for which sending message delivery ack failed
			List<Message> missingAck = messageRepository.getMessagesInStatusesFromSenderSync(currentRemoteNode.getId(), Collections.singletonList(Message.MESSAGE_STATUS_PENDING));
			if (missingAck != null && !missingAck.isEmpty()) {
				for (Message message : missingAck) {
					nearbySignalMessenger.sendMessageAck(message.getPayloadId(), currentRemoteNode.getAddressName(), true, success -> {
						if (success) {
							message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
							messageRepository.updateMessage(message);
						}
					});
				}
			}
		});
	}
}