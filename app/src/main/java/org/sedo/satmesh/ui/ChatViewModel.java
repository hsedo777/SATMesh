package org.sedo.satmesh.ui;

import android.app.Application;
import android.os.Environment;
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
	 * Updates the connection status LiveData based on the latest states from NodeTransientStateRepository.
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
				// Send PersonalInfo if display name is unknown/empty
				if (nearbySignalMessenger.hasSession(remoteNode.getAddressName()) &&
						(remoteNode.getDisplayName() == null || remoteNode.getDisplayName().isEmpty())) {
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
			if (!isSessionSecure) {
				/*
				 * Initiate key exchange if no session. This is a crucial change.
				 * The actual message will be sent after session is established.
				 */
				nearbySignalMessenger.handleInitialKeyExchange(currentRemoteNode.getAddressName());
			}
			message.setStatus(isSessionSecure ? Message.MESSAGE_STATUS_PENDING : Message.MESSAGE_STATUS_PENDING_KEY_EXCHANGE);

			messageRepository.insertMessage(message, success -> {
				if (success) {
					if (isSessionSecure) {
						message.setLastSendingAttempt(System.currentTimeMillis());
						messageRepository.updateMessage(message);
						TextMessage text = TextMessage.newBuilder()
								.setContent(message.getContent())
								.setPayloadId(0L) // Payload ID will be set by NearbySignalManager on send
								.setTimestamp(message.getTimestamp())
								.build();
						nearbySignalMessenger.sendEncryptedTextMessage(currentRemoteNode.getAddressName(), text,
								message.getId(), NearbyManager.TransmissionCallback.NULL_CALLBACK);
					}
				} else {
					// Damageable
					uiMessage.postValue(getApplication().getString(R.string.error_message_persistence_failed));
				}
			});
		});
	}

	/**
	 * Marks, if possible, the specified message as read. To be marked as read,
	 * here is the set of conditions required :
	 * - the message is sent by remote node
	 * - the message is not already in state read
	 * - we successfully sent the message read ack to the remote node
	 * This method is asynchronous.
	 */
	public void markAsRead(@Nullable Message message) {
		if (message == null || message.getId() == null) {
			Log.d(TAG, "Getting null as message to mark read.");
			return;
		}
		if (!Boolean.TRUE.equals(connectionActive.getValue())) {
			// Log.d(TAG, "We can't sent message ack out of secured connection."); -- will appear too much
			return;
		}
		Node remoteNode = remoteNodeLiveData.getValue();
		if (remoteNode == null || remoteNode.getId() == null || remoteNode.getAddressName() == null) {
			Log.e(TAG, "Unable to find the remote node from LiveData : the message can't be marked read.");
			return;
		}
		if (!remoteNode.getId().equals(message.getSenderNodeId())) {
			// This will appear more times, so I didn't log it.
			return;
		}
		if (message.getStatus() == Message.MESSAGE_STATUS_READ) {
			// This will appear more times, that is why I do not log it.
			return;
		}
		if (message.getPayloadId() == null || message.getPayloadId() == 0L) {
			Log.d(TAG, "There is no payload ID bound to this message.");
			return;
		}
		executor.execute(() -> nearbySignalMessenger.sendMessageAck(message.getPayloadId(), remoteNode.getAddressName(), false, null));
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

		nearbySignalMessenger.attemptResendFailedMessagesTo(currentRemoteNode,
				unused -> uiMessage.postValue(getApplication().getString(R.string.resending_failed_messages)));
	}

	public void deleteMessagesById(@NonNull List<Long> ids) {
		if (ids.isEmpty())
			return;
		messageRepository.deleteMessagesById(ids);
	}

	/**
	 * Exports the current chat messages to a plain text file in the device's Downloads directory.
	 * The exported file format includes timestamp, sender, and message content for each message.
	 *
	 * @return A string indicating the export success message with the file path, or null if the export fails
	 * due to missing node information, empty conversation, or an I/O error.
	 */
	public String exportChatMessages() {
		Node hostNode = hostNodeLiveData.getValue();
		Node remoteNode = remoteNodeLiveData.getValue();
		if (hostNode == null || remoteNode == null) {
			Log.e(TAG, "Cannot export messages if the host or remote node is unknown!");
			return null;
		}
		List<Message> messages = conversation.getValue();
		if (messages == null) {
			Log.e(TAG, "Failed to load conversation's messages!");
			return null;
		}
		if (messages.isEmpty()) {
			Log.d(TAG, "There is no message found in the conversation.");
			return null;
		}
		// Prepare the content
		StringBuilder chatContent = new StringBuilder();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

		for (Message message : messages) {
			String senderLabel = (message.getSenderNodeId().equals(hostNode.getId())) ?
					getApplication().getString(R.string.me) : remoteNode.getNonNullName();
			String timestamp = sdf.format(new Date(message.getTimestamp()));
			chatContent.append(String.format("[%s] %s: %s%n", timestamp, senderLabel, message.getContent()));
		}

		// Save to a file
		try {
			// Define directory and filename
			File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
			if (!downloadsDir.exists()) {
				boolean result = downloadsDir.mkdirs(); // Create the directory if it doesn't exist
				Log.d(TAG, "Download directory status: " + result);
			}
			String fileName = "chat_export_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
			File exportFile = new File(downloadsDir, fileName);

			FileWriter writer = new FileWriter(exportFile);
			writer.append(chatContent.toString());
			writer.flush();
			writer.close();

			return getApplication().getString(R.string.chat_export_info, exportFile.getAbsolutePath());
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		return null;
	}

	/**
	 * Clears all chat messages associated with the current remote node.
	 * The operation is performed asynchronously on a background thread.
	 */
	public void clearChat() {
		executor.execute(() -> {
			try {
				Node remoteNode = remoteNodeLiveData.getValue();
				if (remoteNode == null) {
					Log.d(TAG, "Impossible to detect the remote node.");
					return;
				}
				messageRepository.deleteMessagesWithNode(remoteNode.getId());
			} catch (Exception e) {
				Log.e(TAG, e.getMessage(), e);
			}
		});
	}
}