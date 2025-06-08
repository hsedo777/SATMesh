package org.sedo.satmesh.ui;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.connection.Payload;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.nearby.NearbySignalMessenger;
import org.sedo.satmesh.proto.PersonalInfo;
import org.sedo.satmesh.proto.TextMessage;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.MessageRepository;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeState;
import org.whispersystems.libsignal.state.PreKeyBundle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatViewModel extends AndroidViewModel implements NearbySignalMessenger.KeyExchangeListener,
		NearbySignalMessenger.PersonalInfoChangeListener, NearbyManager.DeviceConnectionListener,
		NearbySignalMessenger.MessageSendingListener, NearbySignalMessenger.SignalMessengerCallback,
		NearbySignalMessenger.MessageDecryptionFailureListener {

	private static final String TAG = "ChatViewModel";
	private final MessageRepository messageRepository;
	private final NodeRepository nodeRepository;
	private final NearbyManager nearbyManager;
	private final NearbySignalMessenger nearbySignalMessenger;
	// MediatorLiveData to observe conversation messages from the repository based on nodes.
	private final MediatorLiveData<List<Message>> conversation = new MediatorLiveData<>();
	// LiveData for displaying transient UI messages (e.g., toasts, snack bars).
	private final MutableLiveData<String> uiMessage = new MutableLiveData<>();
	private final MutableLiveData<String> remoteDisplayName = new MutableLiveData<>();
	// Wrap the state connected or not of the implied nodes
	private final MutableLiveData<Boolean> connectionActive = new MutableLiveData<>();
	private final MutableLiveData<NodeState> connectionDetailedStatus = new MutableLiveData<>();
	// Store pending messages
	private final Map<Long, Message> pendingMessages = new HashMap<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	private Node hostNode;
	private Node remoteNode;

	/**
	 * Constructor for ChatViewModel.
	 * Initializes NearbyManager, NearbySignalMessenger, MessageRepository,
	 * and registers as listeners.
	 *
	 * @param application The Application context., see {@link Context#getApplicationContext()}
	 */
	public ChatViewModel(@NonNull Application application) {
		super(application);
		this.messageRepository = new MessageRepository(application);
		this.nodeRepository = new NodeRepository((application));

		/*
		 * The local name may be unknown at this time, but the main activity had initialize
		 * the manager so we are just fetching the existing instance.
		 */
		nearbyManager = NearbyManager.getInstance(application, "");
		nearbySignalMessenger = NearbySignalMessenger.getInstance(SignalManager.getInstance(application), nearbyManager);

		// Add listeners
		nearbySignalMessenger.addKeyExchangeListener(this);
		nearbySignalMessenger.addInfoChangeListener(this);
		nearbySignalMessenger.addMessageSendingListener(this);
		nearbySignalMessenger.addMessengerCallback(this);
		nearbySignalMessenger.addMessageDecryptionFailureListener(this);
		nearbyManager.addDeviceConnectionListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		nearbySignalMessenger.removeKeyExchangeListener(this);
		nearbySignalMessenger.removeInfoChangeListener(this);
		nearbySignalMessenger.removeMessageSendingListener(this);
		nearbySignalMessenger.removeMessengerCallback(this);
		nearbySignalMessenger.removeMessageDecryptionFailureListener(this);
		nearbyManager.removeDeviceConnectionListener(this);
		pendingMessages.clear();
		nodeRepository.clear();
		messageRepository.clear();
		executor.shutdown();
		Log.d(TAG, "ChatViewModel cleared and listeners removed.");
	}

	public NearbyManager getNearbyManager() {
		return nearbyManager;
	}

	public Node getRemoteNode() {
		return remoteNode;
	}

	public Node getHostNode() {
		return hostNode;
	}

	/**
	 * Sets the local (host) and remote nodes for the conversation.
	 * This method should be called when navigating to a specific chat.
	 * This method will start the conversation between the remote and the host nodes..
	 *
	 * @param hostNode   The local device's node.
	 * @param remoteNode The remote device's node for this conversation.
	 */
	public void setConversationNodes(@NonNull Node hostNode, @NonNull Node remoteNode) {
		// In case of reuse of the view model, disconnect from old observer
		if (this.hostNode != null && this.remoteNode != null) {
			conversation.removeSource(messageRepository.getConversationMessages(this.hostNode.getId(), this.remoteNode.getId()));
		}

		this.hostNode = hostNode;
		this.remoteNode = remoteNode;
		// Update remote device display name printing on the view
		remoteDisplayName.postValue(remoteNode.getDisplayName());

		// Add new source to the conversation
		conversation.addSource(messageRepository.getConversationMessages(hostNode.getId(), remoteNode.getId()),
				conversation::setValue);

		connectionActive.postValue(false);
		executor.execute(() -> {
			String endpointId = nearbyManager.getLinkedEndpointId(remoteNode.getAddressName());
			if (endpointId != null) {
				// The nodes can communicate
				nearbySignalMessenger.initiateKeyExchange(endpointId, remoteNode.getAddressName());
			}
		});
	}

	public LiveData<List<Message>> getConversation() {
		return conversation;
	}

	public LiveData<String> getUiMessage() {
		return uiMessage;
	}

	public MutableLiveData<String> getRemoteDisplayName() {
		return remoteDisplayName;
	}

	public MutableLiveData<Boolean> getConnectionActive() {
		return connectionActive;
	}

	public MutableLiveData<NodeState> getConnectionDetailedStatus() {
		return connectionDetailedStatus;
	}

	public boolean areNodesSet() {
		return hostNode != null && remoteNode != null;
	}

	public boolean isRemoteDeviceAddressName(@NonNull String addressName) {
		return remoteNode != null && addressName.equals(remoteNode.getAddressName());
	}


	public void sendMessage(@NonNull String content) {
		if (hostNode == null || remoteNode == null) {
			uiMessage.postValue(getApplication().getString(R.string.error_no_conversation_selected));
			return;
		}

		Message message = new Message();
		message.setContent(content);
		message.setTimestamp(System.currentTimeMillis());
		message.setSenderNodeId(hostNode.getId());
		message.setRecipientNodeId(remoteNode.getId());
		message.setStatus(Message.MESSAGE_STATUS_PENDING);
		message.setType(Message.MESSAGE_TYPE_TEXT);

		messageRepository.insertMessage(message, success -> {
			if (success) {
				// Put the message in pending messages
				pendingMessages.put(message.getId(), message);
				TextMessage text = TextMessage.newBuilder()
						.setContent(message.getContent())
						.setPayloadId(0L)
						.setTimestamp(message.getTimestamp())
						.build();
				nearbySignalMessenger.sendEncryptedMessage(remoteNode.getAddressName(), text, message.getId());
			} else {
				message.setStatus(Message.MESSAGE_STATUS_FAILED);
				messageRepository.updateMessage(message);
			}
		});
	}

	// NearbySignalMessenger.KeyExchangeListener implementation
	@Override
	public void onKeyExchangeInitSuccess(@NonNull String remoteAddressName, long payloadId) {
		Log.d(TAG, "Key exchange initiated successfully with " + remoteAddressName);
		executor.execute(() -> {
			if (remoteNode.getDisplayName() == null) {
				PersonalInfo info = hostNode.toPersonalInfo().toBuilder().setExpectResult(true).build();
				nearbySignalMessenger.sendPersonalInfo(info, remoteAddressName);
			}
		});
	}

	@Override
	public void onKeyExchangeInitFailed(@NonNull String remoteAddressName) {
		Log.w(TAG, "onKeyExchangeInitFailed() : Impossible to encrypt discussion with `" + remoteAddressName + "`");
		if (isRemoteDeviceAddressName(remoteAddressName)) {
			connectionActive.postValue(false);
			uiMessage.postValue(getApplication().getString(R.string.error_key_exchange_failed, remoteAddressName));
			Log.e(TAG, "Key exchange failed with " + remoteAddressName);
		}
	}

	@Override
	public void onReadyToInitiateSession(PreKeyBundle preKeyBundle, String remoteAddressName, long payloadId) {
		Log.d(TAG, "ViewModel: Ready to initiate session with " + remoteAddressName);
		// NearbySignalMessenger automatically handles session initiation.
	}

	@Override
	public void onSessionInitSuccess(String remoteAddressName) {
		Log.d(TAG, "ViewModel: Secure session established with " + remoteAddressName);
		// Notify the UI
		if (isRemoteDeviceAddressName(remoteAddressName)) {
			connectionActive.postValue(true);
			uiMessage.postValue(getApplication().getString(R.string.session_established_with));
			Log.d(TAG, "Signal session established successfully with " + remoteAddressName);
		}
	}

	@Override
	public void onSessionInitFailed(String remoteAddressName, Exception e) {
		Log.e(TAG, "ViewModel: Failed to establish secure session with " + remoteAddressName + ": " + e.getMessage(), e);
		// Notify the UI
		if (isRemoteDeviceAddressName(remoteAddressName)) {
			connectionActive.postValue(false);
			uiMessage.postValue(getApplication().getString(R.string.error_session_failed));
		}
	}

	// Implementation of `NearbySignalMessenger.PersonalInfoChangeListener`
	@Override
	public void onInfoReceived(@NonNull PersonalInfo info) {
		// The implementation is covered by method `SignalMessengerCallback.onPersonalInfoReceived`
	}

	@Override
	public void onInfoSendingSucceed(@NonNull String remoteAddress) {
		Log.d(TAG, "onInfoSendingSucceed() to `" + remoteAddress + "`");
	}

	@Override
	public void onInfoSendingFailed(@NonNull String remoteAddress) {
		// Burk...
		Log.d(TAG, "onInfoSendingFailed() to `" + remoteAddress + "`");
	}

	// Implementation of `NearbyManager.DeviceConnectionListener`
	private void updateConnectionStatusIndicator(@NonNull NodeState state, String deviceAddressName) {
		if (!isRemoteDeviceAddressName(deviceAddressName))
			return;
		connectionDetailedStatus.postValue(state);
	}

	@Override
	public void onConnectionInitiated(String endpointId, String deviceAddressName) {
		updateConnectionStatusIndicator(NodeState.ON_CONNECTION_INITIATED, deviceAddressName);
	}

	@Override
	public void onDeviceConnected(String endpointId, String deviceAddressName) {
		updateConnectionStatusIndicator(NodeState.ON_CONNECTED, deviceAddressName);
	}

	@Override
	public void onConnectionFailed(String deviceAddressName, Status status) {
		updateConnectionStatusIndicator(NodeState.ON_CONNECTION_FAILED, deviceAddressName);
	}

	@Override
	public void onDeviceDisconnected(String endpointId, String deviceAddressName) {
		updateConnectionStatusIndicator(NodeState.ON_DISCONNECTED, deviceAddressName);
	}

	// Implementation of `NearbySignalMessenger.MessageSendingListener`
	@Override
	public void onSendSucceed(@NonNull String recipientAddressName, @NonNull Object id, long payloadId) {
		try {
			Long messageDbId = (Long) id;
			Message messageToUpdate = pendingMessages.get(messageDbId);
			if (messageToUpdate != null) {
				// Maps the payload ID
				messageToUpdate.setPayloadId(payloadId);
				messageRepository.updateMessage(messageToUpdate);
				pendingMessages.remove(messageDbId);
				Log.d(TAG, "Message with ID " + messageDbId + " sent successfully. Status updated to ROUTING.");
			} else {
				Log.w(TAG, "onSendSucceed: Message not found in pending map with ID: " + messageDbId);
			}
		} catch (Exception ignored) {
		}
	}

	@Override
	public void onSendFailed(@NonNull String recipientAddressName, @NonNull Object id) {
		try {
			Long messageDbId = (Long) id;
			Message messageToUpdate = pendingMessages.remove(messageDbId);
			if (messageToUpdate != null) {
				messageToUpdate.setStatus(Message.MESSAGE_STATUS_FAILED);
				messageRepository.updateMessage(messageToUpdate);
				uiMessage.postValue(getApplication().getString(R.string.error_message_send_failed));
				Log.e(TAG, "Message with ID " + messageDbId + " sending failed. Status updated to FAILED.");
			} else {
				Log.w(TAG, "onSendFailed: Message not found in pending map with ID: " + messageDbId);
			}
		} catch (Exception ignored) {
		}
	}

	// Implementation of `NearbySignalMessenger.SignalMessengerCallback`
	@Override
	public void onTextMessageReceived(@NonNull TextMessage message, @NonNull String senderAddressName) {
		Node sender = nodeRepository.findNode(senderAddressName);
		if (sender == null) {
			Log.e(TAG, "onTextMessageReceived() : unable to find the message sender, msg=" + message.getContent());
			return;
		}
		Message receivedMessage = new Message();
		receivedMessage.setPayloadId(message.getPayloadId());
		receivedMessage.setContent(message.getContent());
		receivedMessage.setType(Message.MESSAGE_TYPE_TEXT);
		receivedMessage.setStatus(Message.MESSAGE_STATUS_PENDING); // Temporary value
		receivedMessage.setTimestamp(message.getTimestamp());
		receivedMessage.setRecipientNodeId(hostNode.getId());
		receivedMessage.setSenderNodeId(sender.getId());
		messageRepository.insertMessage(receivedMessage, success -> {
			if (success) {
				nearbySignalMessenger.sendMessageAck(message.getPayloadId(), senderAddressName, true, ackSuccess -> {
					if (ackSuccess) {
						receivedMessage.setStatus(Message.MESSAGE_STATUS_DELIVERED);
						messageRepository.updateMessage(receivedMessage);
					} else {
						Log.w(TAG, "Failed to send delivery ACK for message with payloadId: " + receivedMessage.getPayloadId());
					}
				});
			} else {
				Log.e(TAG, "onTextMessageReceived() : Failed to persist the message, msg=" + message.getContent());
			}
		});
	}

	@Override
	public void onMessageStatusChanged(long payloadId, boolean delivered) {
		// Execute in background
		executor.execute(() -> {
			Message messageToUpdate = messageRepository.getMessageByPayloadId(payloadId);
			if (messageToUpdate != null) {
				if (delivered) {
					messageToUpdate.setStatus(Message.MESSAGE_STATUS_DELIVERED);
					Log.d(TAG, "Message with payloadId " + payloadId + " marked as DELIVERED.");
				} else {
					messageToUpdate.setStatus(Message.MESSAGE_STATUS_READ);
					Log.d(TAG, "Message with payloadId " + payloadId + " marked as READ.");
				}
				messageRepository.updateMessage(messageToUpdate);
			} else {
				Log.w(TAG, "Message with payloadId " + payloadId + " not found in DB for status update.");
			}
		});
	}

	@Override
	public void onPersonalInfoReceived(@NonNull PersonalInfo info) {
		Node nodeToUpdate = nodeRepository.findNode(info.getAddressName());
		if (nodeToUpdate == null) {
			Log.e(TAG, "onPersonalInfoReceived() : unable to find node with address=" + info.getAddressName());
			return;
		}
		// Execute operation in background
		executor.execute(() -> {
			if (!Objects.equals(nodeToUpdate.getDisplayName(), info.getDisplayName())) {
				nodeToUpdate.setDisplayName(info.getDisplayName());
				nodeRepository.update(nodeToUpdate);
				if (Objects.equals(remoteNode.getAddressName(), nodeToUpdate.getAddressName())) {
					remoteDisplayName.postValue(info.getDisplayName());
					Log.d(TAG, "Remote node display name updated to: " + info.getDisplayName());
				}
			}
		});
		if (info.getExpectResult()) {
			nearbySignalMessenger.sendPersonalInfo(hostNode.toPersonalInfo(), info.getAddressName());
		}
	}

	@Override
	public void onPayloadSentConfirmation(@NonNull String recipientAddressName, @NonNull Payload payload) {
		/*
		 * This is just a confirmation for success payload sending
		 * TODO
		 */
	}

	// Implementation of `NearbySignalMessenger.MessageDecryptionFailureListener`
	@Override
	public void onFailure(String senderEndpointId, String senderAddressName, long payloadId, Exception e) {
		Log.e(TAG, "Decryption failed for message from " + senderAddressName + " (payloadId: " + payloadId + "): " + e.getMessage());
		if (isRemoteDeviceAddressName(senderAddressName)) {
			uiMessage.postValue(getApplication().getString(R.string.error_message_decryption_failed));
		}
		// We may add instructions to notify the sender
	}
}
