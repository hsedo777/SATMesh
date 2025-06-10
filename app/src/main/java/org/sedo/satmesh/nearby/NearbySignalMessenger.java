package org.sedo.satmesh.nearby;

import static org.sedo.satmesh.nearby.NearbyManager.DeviceConnectionListener;
import static org.sedo.satmesh.nearby.NearbyManager.PayloadListener;
import static org.sedo.satmesh.proto.NearbyMessageBody.MessageType.ENCRYPTED_MESSAGE;
import static org.sedo.satmesh.signal.SignalManager.getAddress;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.connection.Payload;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.MessageDao;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.NodeDao;
import org.sedo.satmesh.proto.MessageAck;
import org.sedo.satmesh.proto.NearbyMessage;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.PersonalInfo;
import org.sedo.satmesh.proto.PreKeyBundleExchange;
import org.sedo.satmesh.proto.TextMessage;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.NodeState;
import org.sedo.satmesh.ui.data.NodeStateRepository;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyBundle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages Nearby Connections for discovering devices, establishing connections,
 * and exchanging Signal Protocol related messages (PreKeyBundles and encrypted messages).
 * This class handles the low-level communication aspects and integrates with {@link SignalManager}
 * for cryptographic operations and {@link AppDatabase} for persistence.
 */
public class NearbySignalMessenger implements DeviceConnectionListener, PayloadListener {

	private static final String TAG = "NearbySignalMessenger";

	private static volatile NearbySignalMessenger INSTANCE;

	/*
	 * This map tracks CURRENTLY ACTIVE Nearby Connections that also have an established Signal session.
	 * We'll update this map carefully: remote device's SignalProtocolAddress name -> endpointId
	 */
	private final Map<String, String> securedAddressToEndpointId = new HashMap<>();

	private final SignalManager signalManager;
	private final NearbyManager nearbyManager;
	private final Context context;
	private final Node hostNode; // Represents our own device
	private final MessageDao messageDao; // Added for message persistence
	private final NodeDao nodeDao;     // Added for node persistence/status updates
	private final ExecutorService executor;
	// General callback remains for immediate, non-database related notifications
	private final List<SignalMessengerCallback> messengerCallbacks = new CopyOnWriteArrayList<>();

	/**
	 * Private constructor to enforce Singleton pattern.
	 * Takes all necessary dependencies.
	 */
	private NearbySignalMessenger(
			Context context,
			NearbyManager nearbyManager,
			SignalManager signalManager,
			AppDatabase appDatabase,
			Node hostNode) {
		this.context = context.getApplicationContext(); // Use application context to prevent memory leaks
		this.nearbyManager = nearbyManager;
		this.signalManager = signalManager;
		this.hostNode = hostNode;
		this.messageDao = appDatabase.messageDao(); // Initialize DAOs
		this.nodeDao = appDatabase.nodeDao();
		this.executor = Executors.newSingleThreadExecutor(); // Single thread for ordered message processing
		Log.d(TAG, "NearbySignalMessenger instance created with dependencies.");

		// Register this instance as a listener with NearbyManager
		this.nearbyManager.addDeviceConnectionListener(this);
		this.nearbyManager.addPayloadListener(this);
	}

	/**
	 * Returns the singleton instance of NearbySignalMessenger.
	 * This method ensures that the instance is created only once with its required dependencies.
	 * Subsequent calls without dependencies (or with null dependencies) will return the existing instance.
	 *
	 * @param context       The application context. Required for the first initialization.
	 * @param nearbyManager The NearbyManager instance. Required for the first initialization.
	 * @param signalManager The SignalManager instance. Required for the first initialization.
	 * @param appDatabase   The AppDatabase instance. Required for the first initialization.
	 * @param hostNode      The host Node instance. Required for the first initialization.
	 * @return The singleton instance of NearbySignalMessenger.
	 * @throws IllegalStateException if called without all required dependencies when the instance
	 *                               has not yet been created.
	 */
	public static NearbySignalMessenger getInstance(
			Context context,
			NearbyManager nearbyManager,
			SignalManager signalManager,
			AppDatabase appDatabase,
			Node hostNode) {
		if (INSTANCE == null) {
			synchronized (NearbySignalMessenger.class) {
				if (INSTANCE == null) {
					if (context == null || nearbyManager == null || signalManager == null || appDatabase == null || hostNode == null) {
						throw new IllegalStateException("NearbySignalMessenger.getInstance() called for the first time without all required dependencies.");
					}
					INSTANCE = new NearbySignalMessenger(context, nearbyManager, signalManager, appDatabase, hostNode);
				}
			}
		}
		return INSTANCE;
	}

	/**
	 * Returns the singleton instance of NearbySignalMessenger without requiring dependencies.
	 * This method should only be called AFTER the instance has been initialized with getInstance(Context, ...).
	 *
	 * @return The singleton instance of NearbySignalMessenger.
	 * @throws IllegalStateException if called before the instance has been initialized with dependencies.
	 */
	public static NearbySignalMessenger getInstance() {
		if (INSTANCE == null) {
			throw new IllegalStateException("NearbySignalMessenger.getInstance() called without dependencies before initialization.");
		}
		return INSTANCE;
	}

	// Implementation of `NearbyManager.DeviceConnectionListener`
	// This method is called when Nearby Connections initiates a connection.
	@Override
	public void onConnectionInitiated(String endpointId, String deviceAddressName) {
		Log.d(TAG, "Connection initiated with: " + deviceAddressName + " (EndpointId: " + endpointId + ")");
		NodeStateRepository.getInstance().updateTransientNodeState(deviceAddressName, NodeState.ON_CONNECTION_INITIATED);
		// When a connection is initiated, we should accept it.
		// The key exchange logic will happen once connected or upon first message.
		nearbyManager.acceptConnection(endpointId, (id, success) -> {
			if (success) {
				Log.d(TAG, "Accepted connection with " + deviceAddressName);
				// Update node status in DB
				executor.execute(() -> {
					try {
						Node node = nodeDao.getNodeByAddressName(deviceAddressName);
						if (node != null) {
							node.setLastSeen(System.currentTimeMillis());
							node.setConnected(false);
							nodeDao.update(node);
							Log.d(TAG, "Node " + deviceAddressName + " marked as connected in DB.");
						} else {
							// Potentially create a new node if it's the first time discovering it
							// For now, we assume nodes are pre-populated or discovered via other means.
							Log.w(TAG, "Node " + deviceAddressName + " not found in DB on connection initiated. Not marking as connected.");
						}
					} catch (Exception e) {
						Log.e(TAG, "Error updating node status on connection initiation: " + e.getMessage(), e);
					}
				});
			} else {
				Log.e(TAG, "Failed to accept connection with " + deviceAddressName);
			}
		});
	}

	// This method is called when Nearby Connections establishes a connection.
	@Override
	public void onDeviceConnected(String endpointId, String deviceAddressName) {
		Log.d(TAG, "Device connected: " + deviceAddressName + " (EndpointId: " + endpointId + ")");
		// No explicit key exchange call here. Logic moved to handleReceivedNearbyMessage
		// or sendEncryptedMessage which will check for active session.
		// Update node status in DB
		executor.execute(() -> {
			try {
				Node node = nodeDao.getNodeByAddressName(deviceAddressName);
				if (node != null) {
					node.setLastSeen(System.currentTimeMillis());
					node.setConnected(true);
					nodeDao.update(node);
					Log.d(TAG, "Node " + deviceAddressName + " marked as connected in DB.");
				} else {
					// Create a new node entity if it doesn't exist.
					// This covers cases where a device is connected but not yet explicitly "discovered"
					// or persisted from a previous run.
					Node newNode = new Node();
					newNode.setAddressName(deviceAddressName);
					newNode.setLastSeen(System.currentTimeMillis());
					newNode.setConnected(true);
					newNode.setId(nodeDao.insert(newNode));
					Log.d(TAG, "New Node " + deviceAddressName + " inserted and marked as connected in DB.");
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating/inserting node status on device connected: " + e.getMessage(), e);
			}
		});
		NodeStateRepository.getInstance().updateTransientNodeState(deviceAddressName, NodeState.ON_CONNECTED);
	}

	// This method is called when Nearby Connections fails to establish a connection.
	@Override
	public void onConnectionFailed(String deviceAddressName, Status status) {
		Log.e(TAG, "Connection failed for " + deviceAddressName + " with status: " + status);
		NodeStateRepository.getInstance().updateTransientNodeState(deviceAddressName, NodeState.ON_CONNECTION_FAILED);
		executor.execute(() -> {
			try {
				Node node = nodeDao.getNodeByAddressName(deviceAddressName);
				if (node != null) {
					node.setConnected(false); // Mark as disconnected
					nodeDao.update(node);
					Log.d(TAG, "Node " + deviceAddressName + " marked as disconnected in DB on connection failed.");
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating node status on connection failed: " + e.getMessage(), e);
			}
		});
		// Remove from secured map if it was there (shouldn't be, but as a safeguard)
		securedAddressToEndpointId.remove(deviceAddressName);
		messengerCallbacks.forEach(cb -> cb.onConnectionFailure(deviceAddressName, status));
	}

	// This method is called when Nearby Connections detects a disconnection.
	@Override
	public void onDeviceDisconnected(String endpointId, String deviceAddressName) {
		Log.d(TAG, "Device disconnected: " + deviceAddressName + " (EndpointId: " + endpointId + ")");
		NodeStateRepository.getInstance().updateTransientNodeState(deviceAddressName, NodeState.ON_DISCONNECTED);
		executor.execute(() -> {
			try {
				Node node = nodeDao.getNodeByAddressName(deviceAddressName);
				if (node != null) {
					node.setConnected(false); // Mark as disconnected
					nodeDao.update(node);
					Log.d(TAG, "Node " + deviceAddressName + " marked as disconnected in DB.");
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating node status on device disconnected: " + e.getMessage(), e);
			}
		});
		// Crucial: remove from secured map when disconnected
		securedAddressToEndpointId.remove(deviceAddressName);
		messengerCallbacks.forEach(cb -> cb.onDeviceDisconnected(deviceAddressName));
	}

	// Implementation of `NearbyManager.PayloadListener`
	@Override
	public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
		executor.execute(() -> handleReceivedNearbyMessage(endpointId, payload));
	}

	public void addMessengerCallback(@NonNull SignalMessengerCallback messengerCallback) {
		messengerCallbacks.add(messengerCallback);
	}

	public void removeMessengerCallback(@NonNull SignalMessengerCallback messengerCallback) {
		messengerCallbacks.remove(messengerCallback);
	}

	/**
	 * Remove all listeners this instance put on the `NearbyManager`
	 */
	public void clearNearbyManagerListeners() {
		nearbyManager.removePayloadListener(this);
		nearbyManager.removeDeviceConnectionListener(this);
	}

	/**
	 * Shuts down the messenger, including its executor and listeners.
	 * Should be called when the service is destroyed.
	 */
	public void shutdown() {
		executor.shutdown();
		clearNearbyManagerListeners();
		// Clear the map on shutdown
		securedAddressToEndpointId.clear();
		NodeStateRepository.getInstance().clearTransientStates().shutdown();
		Log.d(TAG, "NearbySignalMessenger shut down.");
	}

	/**
	 * Wraps and sends an encrypted message or key exchange message.
	 * This method handles the serialization of the NearbyMessage object and sends it via Nearby Connections.
	 *
	 * @param nearbyMessage        The NearbyMessage protobuf object to send.
	 * @param recipientAddressName The Signal Protocol address name of the recipient.
	 * @param callback             Callback for success or failure of the Nearby Connections send operation.
	 */
	private void sendNearbyMessageInternal(@NonNull NearbyMessage nearbyMessage,
	                                       @NonNull String recipientAddressName,
	                                       @NonNull BiConsumer<Payload, Boolean> callback) {
		final String endpointId = securedAddressToEndpointId.get(recipientAddressName);
		if (endpointId == null) {
			Log.e(TAG, "Cannot send message to " + recipientAddressName + ": No active secured connection (endpointId not found in map).");
			callback.accept(null, false); // Notify failure
			messengerCallbacks.forEach(cb -> cb.onSendMessageFailed(recipientAddressName, "No active secured connection."));
			return;
		}

		try {
			byte[] messageBytes = nearbyMessage.toByteArray();
			nearbyManager.sendNearbyMessage(endpointId, messageBytes, callback);
			Log.d(TAG, "NearbyMessage (type: " + (nearbyMessage.getExchange() ? "KEY_EXCHANGE" : "ENCRYPTED_DATA") + ") sent to " + recipientAddressName);
		} catch (Exception e) {
			Log.e(TAG, "Error serializing or sending NearbyMessage to " + recipientAddressName, e);
			callback.accept(null, false); // Notify failure
			messengerCallbacks.forEach(cb -> cb.onSendMessageFailed(recipientAddressName, "Serialization or send error: " + e.getMessage()));
		}
	}

	/**
	 * Helper method to update message status in the database.
	 *
	 * @param messageDbId The ID of the message in the local database.
	 * @param status      The new status to set.
	 * @param payloadId   The payload ID from Nearby Connections (can be null if not applicable).
	 */
	private void updateMessageStatus(long messageDbId, int status, @Nullable Long payloadId) {
		executor.execute(() -> {
			try {
				Message msg = messageDao.getMessageById(messageDbId);
				if (msg != null) {
					msg.setStatus(status);
					if (payloadId != null) {
						msg.setPayloadId(payloadId); // Set the Nearby Payload ID
					}
					messageDao.update(msg);
					Log.d(TAG, "Message ID " + messageDbId + " status updated to " + status + ".");
				} else {
					Log.w(TAG, "Message with ID " + messageDbId + " not found for status update.");
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating message status for ID " + messageDbId + ": " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Handles the initial key exchange with a remote device.
	 * This is called when a message needs to be sent but no active Signal session exists.
	 *
	 * @param endpointId        The ID of the connected endpoint.
	 * @param remoteAddressName The SignalProtocolAddress name of the remote device.
	 */
	private void handleInitialKeyExchange(@NonNull String endpointId, @NonNull String remoteAddressName) {
		// This method is called from an executor thread, so direct calls to `signalManager` are fine.
		try {
			Log.d(TAG, "handleInitialKeyExchange() from " + hostNode.getAddressName() + " to " + remoteAddressName + '@' + endpointId);
			SignalProtocolAddress remoteAddress = getAddress(remoteAddressName);
			if (signalManager.hasActiveSession(remoteAddress)) {
				Log.d(TAG, "Session already active with " + remoteAddressName + ". No key exchange needed.");
				return;
			}

			PreKeyBundle localPreKeyBundle = signalManager.generateOurPreKeyBundle();
			byte[] serializedPreKeyBundle = signalManager.serializePreKeyBundle(localPreKeyBundle);

			PreKeyBundleExchange preKeyBundleExchange = PreKeyBundleExchange.newBuilder()
					.setPreKeyBundle(ByteString.copyFrom(serializedPreKeyBundle))
					.build();

			NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
					.setExchange(true) // Indicate it's a key exchange message
					.setKeyExchangeMessage(preKeyBundleExchange)
					.build();

			sendNearbyMessageInternal(nearbyMessage, remoteAddressName, (payload, success) -> {
				if (success) {
					Log.d(TAG, "Key exchange message sent to: " + remoteAddressName + ". Payload ID: " + payload.getId());
					messengerCallbacks.forEach(cb -> cb.onKeyExchangeInitiated(remoteAddressName, payload.getId()));
				} else {
					Log.e(TAG, "Failed to send key exchange message to " + remoteAddressName);
					messengerCallbacks.forEach(cb -> cb.onKeyExchangeFailed(remoteAddressName, "Nearby send failed."));
				}
			});

		} catch (Exception e) {
			Log.e(TAG, "Error initiating key exchange with " + remoteAddressName, e);
			messengerCallbacks.forEach(cb -> cb.onKeyExchangeFailed(remoteAddressName, "Generation/Serialization error: " + e.getMessage()));
		}
	}

	/**
	 * Sends an encrypted message to a specific remote device.
	 * Before calling this method, the message should already be stored in the database
	 * in a "pending" or "sending" state.
	 *
	 * @param recipientAddressName The SignalProtocolAddress name of the recipient device.
	 * @param textMessage          The plaintext TextMessage to encrypt and send.
	 * @param messageDbId          The ID of the message in the local database (used for updates).
	 */
	public void sendEncryptedTextMessage(@NonNull String recipientAddressName, @NonNull TextMessage textMessage, long messageDbId) {
		/*
		 * Important : Cause the message must be wrapped in the payload, it
		 * is not possible to write the payload ID in the message. But after creating
		 * the payload, the local message will be set up to date with the correct payload
		 * ID.
		 */
		executor.execute(() -> {
			SignalProtocolAddress recipientAddress = getAddress(recipientAddressName);
			try {
				// Ensure a session exists before attempting to encrypt
				if (!signalManager.hasActiveSession(recipientAddress)) {
					Log.i(TAG, "No active Signal session with " + recipientAddressName + ". Attempting to establish session first.");
					// Initiate key exchange if no session. This is a crucial change.
					// The actual message will be sent *after* session is established.
					// This assumes the UI will re-attempt sending once session is ready, or queue it.
					// For robustness, we could queue the message here, but for now, we just log and return.
					handleInitialKeyExchange(Objects.requireNonNull(securedAddressToEndpointId.get(recipientAddressName)), recipientAddressName);
					// Update message status to "FAILED" or "PENDING_KEY_EXCHANGE" in DB.
					updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_PENDING_KEY_EXCHANGE, null);
					messengerCallbacks.forEach(cb -> cb.onSendMessageFailed(recipientAddressName, "No active Signal session; initiating key exchange."));
					return;
				}

				// Construct NearbyMessageBody with the actual TextMessage
				NearbyMessageBody messageBody = NearbyMessageBody.newBuilder()
						.setMessageType(ENCRYPTED_MESSAGE)
						.setEncryptedData(textMessage.toByteString())
						.build();

				// Encrypt the NearbyMessageBody
				CiphertextMessage cipherMessage = signalManager.encryptMessage(recipientAddress, messageBody.toByteArray());

				// Wrap in NearbyMessage
				NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
						.setExchange(false) // This is not a key exchange message
						.setBody(ByteString.copyFrom(cipherMessage.serialize()))
						.build();

				// Send the wrapped message
				sendNearbyMessageInternal(nearbyMessage, recipientAddressName, (payload, success) -> {
					if (success) {
						Log.d(TAG, "TextMessage sent successfully to " + recipientAddressName + ". Payload ID: " + payload.getId());
						// Update message in DB with sent status and payload ID. Set status pending to message ack
						updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_PENDING, payload.getId());
						messengerCallbacks.forEach(cb -> cb.onPayloadSentConfirmation(recipientAddressName, payload));
					} else {
						Log.e(TAG, "Failed to send TextMessage to " + recipientAddressName + " (Payload ID: " + (payload != null ? payload.getId() : "N/A") + ")");
						// Update message in DB with failed status
						updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, null);
						messengerCallbacks.forEach(cb -> cb.onSendMessageFailed(recipientAddressName, "Nearby send failed."));
					}
				});

			} catch (NoSessionException e) {
				// This case should ideally be caught by hasActiveSession() check, but good for robustness
				Log.e(TAG, "No Signal session with " + recipientAddressName + " to encrypt message. Error: " + e.getMessage());
				updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, null);
				messengerCallbacks.forEach(cb -> cb.onSendMessageFailed(recipientAddressName, "No Signal session for encryption."));
			} catch (Exception e) {
				Log.e(TAG, "Error encrypting or sending TextMessage to " + recipientAddressName, e);
				updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, null);
				messengerCallbacks.forEach(cb -> cb.onSendMessageFailed(recipientAddressName, "Encryption error: " + e.getMessage()));
			}
		});
	}

	/**
	 * Sends a message acknowledgment (delivered or read) to a recipient.
	 *
	 * @param originalPayloadId    The ID of the original message payload being acknowledged.
	 * @param recipientAddressName The Signal Protocol address name of the message recipient.
	 * @param delivered            True if it's a delivered ACK, false if it's a read ACK.
	 * @param callback             Callback to notify the caller of the success/failure of sending the ACK.
	 */
	public void sendMessageAck(long originalPayloadId, @NonNull String recipientAddressName, boolean delivered, @NonNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			SignalProtocolAddress recipientAddress = getAddress(recipientAddressName);
			try {
				if (!signalManager.hasActiveSession(recipientAddress)) {
					Log.w(TAG, "No active Signal session with " + recipientAddressName + " to send ACK. Skipping.");
					callback.accept(false); // Notify failure
					return;
				}

				MessageAck messageAck = MessageAck.newBuilder().setPayloadId(originalPayloadId).build();

				NearbyMessageBody messageBody = NearbyMessageBody.newBuilder()
						.setMessageType(delivered ? NearbyMessageBody.MessageType.MESSAGE_DELIVERED_ACK : NearbyMessageBody.MessageType.MESSAGE_READ_ACK)
						.setEncryptedData(messageAck.toByteString())
						.build();

				CiphertextMessage cipherMessage = signalManager.encryptMessage(recipientAddress, messageBody.toByteArray());

				NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
						.setExchange(false)
						.setBody(ByteString.copyFrom(cipherMessage.serialize()))
						.build();

				sendNearbyMessageInternal(nearbyMessage, recipientAddressName, (payload, success) -> {
					if (success) {
						Log.d(TAG, (delivered ? "Delivered" : "Read") + " ACK sent for payload " + originalPayloadId + " to " + recipientAddressName);
					} else {
						Log.e(TAG, "Failed to send " + (delivered ? "Delivered" : "Read") + " ACK for payload " + originalPayloadId + " to " + recipientAddressName);
					}
					callback.accept(success);
				});

			} catch (Exception e) {
				Log.e(TAG, "Error sending ACK for payload " + originalPayloadId + " to " + recipientAddressName, e);
				callback.accept(false); // Notify failure
			}
		});
	}

	/**
	 * Sends personal information to a recipient.
	 *
	 * @param info                 The PersonalInfo protobuf object to send.
	 * @param recipientAddressName The Signal Protocol address name of the recipient.
	 */
	public void sendPersonalInfo(@NonNull PersonalInfo info, @NonNull String recipientAddressName) {
		executor.execute(() -> {
			SignalProtocolAddress recipientAddress = getAddress(recipientAddressName);
			try {
				if (!signalManager.hasActiveSession(recipientAddress)) {
					Log.w(TAG, "No active Signal session with " + recipientAddressName + " to send PersonalInfo. Skipping.");
					messengerCallbacks.forEach(cb -> cb.onPersonalInfoSendingFailed(recipientAddressName, "No active session."));
					return;
				}

				NearbyMessageBody messageBody = NearbyMessageBody.newBuilder()
						.setMessageType(NearbyMessageBody.MessageType.PERSONAL_INFO)
						.setEncryptedData(info.toByteString())
						.build();

				CiphertextMessage cipherMessage = signalManager.encryptMessage(recipientAddress, messageBody.toByteArray());

				NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
						.setExchange(false)
						.setBody(ByteString.copyFrom(cipherMessage.serialize()))
						.build();

				sendNearbyMessageInternal(nearbyMessage, recipientAddressName, (payload, success) -> {
					if (success) {
						Log.d(TAG, "PersonalInfo sent successfully to " + recipientAddressName);
						messengerCallbacks.forEach(cb -> cb.onPersonalInfoSendingSuccess(recipientAddressName));
					} else {
						Log.e(TAG, "Failed to send PersonalInfo to " + recipientAddressName);
						messengerCallbacks.forEach(cb -> cb.onPersonalInfoSendingFailed(recipientAddressName, "Nearby send failed."));
					}
				});

			} catch (Exception e) {
				Log.e(TAG, "Error sending PersonalInfo to " + recipientAddressName, e);
				messengerCallbacks.forEach(cb -> cb.onPersonalInfoSendingFailed(recipientAddressName, "Encryption error: " + e.getMessage()));
			}
		});
	}

	/**
	 * Handles any incoming Nearby Payload, parsing it into a NearbyMessage
	 * and directing it to the correct handler based on its type (key exchange or encrypted message).
	 *
	 * @param endpointId The ID of the Nearby endpoint from which the payload was received.
	 * @param payload    The received Nearby Payload.
	 */
	private void handleReceivedNearbyMessage(@NonNull String endpointId, @NonNull Payload payload) {
		// This runs on the executor thread
		try {
			byte[] data = payload.asBytes();
			if (data == null) {
				Log.e(TAG, "Received null data from payload " + payload.getId() + " from " + endpointId);
				messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, "N/A", payload.getId(), "Received null data."));
				return;
			}

			Log.d(TAG, "Received bytes (Payload ID: " + payload.getId() + ", Size: " + data.length + ") from " + endpointId);
			NearbyMessage nearbyMessage = NearbyMessage.parseFrom(data);
			String senderAddressName = nearbyManager.getEndpointName(endpointId); // Get Signal address name

			if (senderAddressName == null) {
				Log.e(TAG, "Sender address name is null for endpoint " + endpointId + ". Cannot process message.");
				messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, "N/A", payload.getId(), "Sender address unknown."));
				return;
			}

			// Update node's last seen and connected status in DB
			Node node = nodeDao.getNodeByAddressName(senderAddressName);
			if (node != null) {
				node.setLastSeen(System.currentTimeMillis());
				node.setConnected(true); // Assuming message means connected
				nodeDao.update(node);
			} else {
				// Insert new node if not found
				Node newNode = new Node();
				newNode.setAddressName(senderAddressName);
				newNode.setLastSeen(System.currentTimeMillis());
				newNode.setConnected(true);
				newNode.setId(nodeDao.insert(newNode));
				Log.d(TAG, "New node " + senderAddressName + " inserted in DB upon message receipt.");
			}


			if (nearbyMessage.getExchange()) {
				// It's a key exchange message
				if (!nearbyMessage.hasKeyExchangeMessage()) {
					Log.e(TAG, "Received NearbyMessage with exchange=true but no key_exchange_message content from " + senderAddressName);
					messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, senderAddressName, payload.getId(), "Malformed key exchange message."));
					return;
				}
				handleReceivedKeyExchange(endpointId, senderAddressName, nearbyMessage.getKeyExchangeMessage().getPreKeyBundle().toByteArray(), payload.getId());
			} else {
				// It's an encrypted message body
				if (!nearbyMessage.hasBody()) {
					Log.e(TAG, "Received NearbyMessage with exchange=false but no body content from " + senderAddressName);
					messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, senderAddressName, payload.getId(), "Malformed encrypted message."));
					return;
				}
				handleReceivedEncryptedMessage(endpointId, senderAddressName, nearbyMessage.getBody().toByteArray(), payload.getId());
			}
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse NearbyMessage from " + endpointId + ": " + e.getMessage(), e);
			// Notify UI about a general message processing failure (could be a corrupt message)
			messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, nearbyManager.getEndpointName(endpointId), payload.getId(), "Protocol buffer parsing error."));
		} catch (Exception e) {
			Log.e(TAG, "Unexpected error handling received NearbyMessage from " + endpointId + ": " + e.getMessage(), e);
			messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, nearbyManager.getEndpointName(endpointId), payload.getId(), "Unexpected error during message handling."));
		}
	}

	/**
	 * Handles a received PreKeyBundle from a remote device, establishing or updating a Signal session.
	 *
	 * @param endpointId        The ID of the Nearby endpoint.
	 * @param senderAddressName The SignalProtocolAddress name of the sender.
	 * @param preKeyBundleData  The serialized PreKeyBundle.
	 * @param payloadId         The Nearby Payload ID.
	 */
	private void handleReceivedKeyExchange(@NonNull String endpointId, @NonNull String senderAddressName, @NonNull byte[] preKeyBundleData, long payloadId) {
		// This runs on the executor thread
		try {
			Log.d(TAG, "Received Key Exchange from: " + senderAddressName + " (EndpointId: " + endpointId + "). Payload ID: " + payloadId);
			SignalProtocolAddress remoteAddress = getAddress(senderAddressName);

			// Process the received PreKeyBundle using SignalManager
			// The method establishSessionFromRemotePreKeyBundle handles session establishment/updates
			signalManager.establishSessionFromRemotePreKeyBundle(remoteAddress, signalManager.deserializePreKeyBundle(preKeyBundleData));

			// Mark the session as secured. This is where securedAddressToEndpointId is updated.
			securedAddressToEndpointId.put(senderAddressName, endpointId);
			Log.d(TAG, "Signal session established/updated with " + senderAddressName + ". Endpoint mapped.");
			messengerCallbacks.forEach(cb -> cb.onKeyExchangeSuccess(senderAddressName));

			// Update node status in DB
			Node node = nodeDao.getNodeByAddressName(senderAddressName);
			if (node != null) {
				node.setLastSeen(System.currentTimeMillis());
				node.setConnected(true);
				nodeDao.update(node);
				Log.d(TAG, "Node " + senderAddressName + " updated with hasSignalSession=true.");
			} else {
				Log.e(TAG, "Node " + senderAddressName + " not found in DB after key exchange. This should not happen.");
			}
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to deserialize PreKeyBundle from " + senderAddressName + ": " + e.getMessage(), e);
			messengerCallbacks.forEach(cb -> cb.onKeyExchangeFailed(senderAddressName, "Invalid PreKeyBundle format."));
		} catch (InvalidKeyException e) {
			Log.e(TAG, "Invalid key in PreKeyBundle from " + senderAddressName + ": " + e.getMessage(), e);
			messengerCallbacks.forEach(cb -> cb.onKeyExchangeFailed(senderAddressName, "Invalid key in PreKeyBundle."));
		} catch (Exception e) {
			Log.e(TAG, "Error processing key exchange from " + senderAddressName + ": " + e.getMessage(), e);
			messengerCallbacks.forEach(cb -> cb.onKeyExchangeFailed(senderAddressName, "Unexpected error during key exchange."));
		}
	}

	/**
	 * Handles an incoming encrypted message payload.
	 * It decrypts the message, parses its body, and processes it based on its type.
	 *
	 * @param endpointId        The ID of the Nearby endpoint.
	 * @param senderAddressName The SignalProtocolAddress.name of the sender.
	 * @param cipherData        The raw encrypted bytes of the CiphertextMessage (which contains NearbyMessageBody).
	 * @param payloadId         The Nearby Payload ID.
	 */
	private void handleReceivedEncryptedMessage(@NonNull String endpointId, @NonNull String senderAddressName, @NonNull byte[] cipherData, long payloadId) {
		// This runs on the executor thread
		try {
			Log.d(TAG, "Received Encrypted Message from: " + senderAddressName + " (EndpointId: " + endpointId + "), Payload ID: " + payloadId + ", Size: " + cipherData.length);

			/*
			 * Reconstruct CiphertextMessage from raw bytes
			 * Note: We need to properly determine if it's PREKEY_TYPE or WHISPER_TYPE.
			 * For a first received message in a new session (after PreKeyBundle exchange),
			 * it will be PREKEY_TYPE. Subsequent messages are WHISPER_TYPE.
			 * LibSignal's SessionCipher.decrypt() handles this internally.
			 */
			CiphertextMessage receivedCipherMessage = new SignalMessage((cipherData)); // Deserialize

			SignalProtocolAddress senderAddress = getAddress(senderAddressName);
			byte[] decryptedBytes = signalManager.decryptMessage(senderAddress, receivedCipherMessage);
			NearbyMessageBody decryptedMessageBody = NearbyMessageBody.parseFrom(decryptedBytes);

			// Now, we have the decrypted content (NearbyMessageBody).
			// We need to persist the received message if it's a TextMessage.
			// For ACKs or Info, we update existing entries or just notify.

			Message message;
			switch (decryptedMessageBody.getMessageType()) {
				case ENCRYPTED_MESSAGE:
					TextMessage textMessage = TextMessage.parseFrom(decryptedMessageBody.getEncryptedData())
							.toBuilder()
							.setPayloadId(payloadId) // Set the payload ID here
							.build();
					Node senderNode = nodeDao.getNodeByAddressName(senderAddressName);
					if (senderNode == null) {
						Log.e(TAG, "Failed to identify, in DB, the sender node at address " + senderAddress + " msg.payloadId=" + payloadId);
						return;
					}

					// Persist the received text message
					message = new Message();
					message.setPayloadId(payloadId);
					message.setStatus(Message.MESSAGE_STATUS_PENDING);
					message.setSenderNodeId(senderNode.getId());
					message.setRecipientNodeId(hostNode.getId());
					message.setType(Message.MESSAGE_TYPE_TEXT);
					message.setContent(textMessage.getContent());
					message.setTimestamp(textMessage.getTimestamp());
					message.setId(messageDao.insert(message));
					Log.d(TAG, "Received and persisted TextMessage from " + senderAddressName + ": " + textMessage.getContent());
					messengerCallbacks.forEach(cb -> cb.onTextMessageReceived(textMessage, senderAddressName));
					break;
				case MESSAGE_DELIVERED_ACK:
					MessageAck deliveredAck = MessageAck.parseFrom(decryptedMessageBody.getEncryptedData());
					// Update status of the original message in DB
					message = messageDao.getMessageByPayloadId(deliveredAck.getPayloadId());
					if (message == null) {
						Log.e(TAG, "Receiving message delivered ack for non-identified payload id=" + deliveredAck.getPayloadId());
						return;
					}
					message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
					messageDao.update(message);
					Log.d(TAG, "Received Delivered ACK for payload " + deliveredAck.getPayloadId() + " from " + senderAddressName);
					messengerCallbacks.forEach(cb -> cb.onMessageStatusChanged(deliveredAck.getPayloadId(), true));
					break;
				case MESSAGE_READ_ACK:
					MessageAck readAck = MessageAck.parseFrom(decryptedMessageBody.getEncryptedData());
					// Update status of the original message in DB
					message = messageDao.getMessageByPayloadId(readAck.getPayloadId());
					if (message == null) {
						Log.e(TAG, "Receiving message read ack for non-identified payload id=" + readAck.getPayloadId());
						return;
					}
					message.setStatus(Message.MESSAGE_STATUS_READ);
					messageDao.update(message);
					Log.d(TAG, "Received Read ACK for payload " + readAck.getPayloadId() + " from " + senderAddressName);
					messengerCallbacks.forEach(cb -> cb.onMessageStatusChanged(readAck.getPayloadId(), false));
					break;
				case PERSONAL_INFO:
					PersonalInfo personalInfo = PersonalInfo.parseFrom(decryptedMessageBody.getEncryptedData());
					// Update node's display name and potentially other info in DB
					Node nodeToUpdate = nodeDao.getNodeByAddressName(senderAddressName);
					if (nodeToUpdate != null) {
						nodeToUpdate.setDisplayName(personalInfo.getDisplayName());
						nodeToUpdate.setLastSeen(System.currentTimeMillis()); // Update last seen as well
						nodeDao.update(nodeToUpdate);
						Log.d(TAG, "Received and updated PersonalInfo for " + senderAddressName + ": " + personalInfo.getDisplayName());
					} else {
						// This case might not happen cause exchanging personal info require secured session and the session requires node persistence.
						Log.d(TAG, "Received PersonalInfo for new node " + senderAddressName + ": " + personalInfo.getDisplayName());
						return;
					}
					messengerCallbacks.forEach(cb -> cb.onPersonalInfoReceived(personalInfo));
					break;
				case TYPING_INDICATOR:
					// TypingIndicator typingIndicator = TypingIndicator.parseFrom(decryptedMessageBody.getEncryptedData());
					// Log.d(TAG, "Received TypingIndicator from " + senderAddressName + ": " + typingIndicator.getIsTyping());
					// TODO: Notify UI via messengerCallbacks to show/hide typing indicator if needed
					break;
				case ROUTE_DISCOVERY:
					Log.d(TAG, "Received ROUTE_DISCOVERY (to be implemented) from " + senderAddressName);
					// This involve processing routing data and potentially updating a routing table in DB
					break;
				case UNKNOWN:
				default:
					Log.w(TAG, "Received UNKNOWN or unhandled message type: " + decryptedMessageBody.getMessageType() + " from " + senderAddressName + ". Payload ID: " + payloadId);
					break;
			}
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse decrypted message body from " + senderAddressName + " (Payload ID: " + payloadId + "): " + e.getMessage(), e);
			messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, senderAddressName, payloadId, "Invalid message body format."));
		} catch (NoSessionException e) {
			Log.e(TAG, "No Signal session to decrypt message from " + senderAddressName + " (Payload ID: " + payloadId + "): " + e.getMessage(), e);
			// This could happen if sender sends message before session is fully established, or if our session was lost.
			// We should re-initiate key exchange if possible, or request sender to re-send.
			// For now, just log and notify failure.
			messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, senderAddressName, payloadId, "No Signal session to decrypt."));
			// Optionally, try to initiate key exchange again to fix session
			executor.execute(() -> handleInitialKeyExchange(endpointId, senderAddressName));
		} catch (Exception e) {
			Log.e(TAG, "Error processing encrypted message from " + senderAddressName + " (Payload ID: " + payloadId + "): " + e.getMessage(), e);
			messengerCallbacks.forEach(cb -> cb.onMessageDecryptionFailure(endpointId, senderAddressName, payloadId, "Unexpected error during decryption."));
		}
	}

	public interface SignalMessengerCallback {
		/**
		 * Called when a text message is received and successfully processed.
		 * The message is already persisted in the database.
		 *
		 * @param message           The decrypted TextMessage.
		 * @param senderAddressName The Signal address name of the sender.
		 */
		void onTextMessageReceived(@NonNull TextMessage message, @NonNull String senderAddressName);

		/**
		 * Called when the status of an outgoing message (sent or read ACK) changes.
		 * The message status is already updated in the database.
		 *
		 * @param payloadId The original message's payload ID.
		 * @param delivered True if delivered, false if read.
		 */
		void onMessageStatusChanged(long payloadId, boolean delivered);

		/**
		 * Called when personal information is received and processed.
		 * The node's information is already updated in the database.
		 *
		 * @param info The PersonalInfo received.
		 */
		void onPersonalInfoReceived(@NonNull PersonalInfo info);

		/**
		 * Called when a payload has been successfully sent over the network to a neighbor.
		 * The local message status has been updated in the database.
		 *
		 * @param recipientAddressName The Signal address name of the recipient.
		 * @param payload              The payload that was successfully sent.
		 */
		void onPayloadSentConfirmation(@NonNull String recipientAddressName, @NonNull Payload payload);

		/**
		 * Called when sending a message (any type) fails.
		 * The local message status (if applicable) has been updated in the database.
		 *
		 * @param recipientAddressName The Signal address name of the recipient.
		 * @param reason               A string describing the reason for failure.
		 */
		void onSendMessageFailed(@NonNull String recipientAddressName, @NonNull String reason);

		/**
		 * Called when key exchange initiation is successful (our PreKeyBundle sent).
		 *
		 * @param remoteAddressName The Signal address name of the remote device.
		 * @param payloadId         The ID of the sent payload.
		 */
		void onKeyExchangeInitiated(@NonNull String remoteAddressName, long payloadId);

		/**
		 * Called when key exchange fails (either sending our bundle or processing remote's).
		 *
		 * @param remoteAddressName The Signal address name of the remote device.
		 * @param reason            A string describing the reason for failure.
		 */
		void onKeyExchangeFailed(@NonNull String remoteAddressName, @NonNull String reason);

		/**
		 * Called when a Signal session is successfully established or updated with a remote device.
		 *
		 * @param remoteAddressName The Signal address name of the remote device.
		 */
		void onKeyExchangeSuccess(@NonNull String remoteAddressName);

		/**
		 * Called when a received encrypted message fails to decrypt or be processed.
		 *
		 * @param senderEndpointId  The endpoint ID of the sender.
		 * @param senderAddressName The Signal address name of the sender.
		 * @param payloadId         The ID of the payload that failed.
		 * @param reason            A string describing the reason for failure.
		 */
		void onMessageDecryptionFailure(String senderEndpointId, String senderAddressName, long payloadId, String reason);

		/**
		 * Called when a Nearby Connection fails for a given device.
		 *
		 * @param deviceAddressName The Signal address name of the device.
		 * @param status            The Nearby Status object.
		 */
		void onConnectionFailure(@NonNull String deviceAddressName, @NonNull Status status);

		/**
		 * Called when a Nearby device gets disconnected.
		 *
		 * @param deviceAddressName The Signal address name of the disconnected device.
		 */
		void onDeviceDisconnected(@NonNull String deviceAddressName);

		/**
		 * Called when sending PersonalInfo succeeds.
		 *
		 * @param remoteAddressName The Signal address name of the recipient.
		 */
		void onPersonalInfoSendingSuccess(@NonNull String remoteAddressName);

		/**
		 * Called when sending PersonalInfo fails.
		 *
		 * @param remoteAddressName The Signal address name of the recipient.
		 * @param reason            A string describing the reason for failure.
		 */
		void onPersonalInfoSendingFailed(@NonNull String remoteAddressName, @NonNull String reason);
	}
}
