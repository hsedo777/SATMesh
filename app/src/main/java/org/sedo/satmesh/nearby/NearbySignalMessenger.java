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
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.SignalKeyExchangeState;
import org.sedo.satmesh.proto.MessageAck;
import org.sedo.satmesh.proto.NearbyMessage;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.PersonalInfo;
import org.sedo.satmesh.proto.PreKeyBundleExchange;
import org.sedo.satmesh.proto.RouteRequestMessage;
import org.sedo.satmesh.proto.RouteResponseMessage;
import org.sedo.satmesh.proto.RoutedMessage;
import org.sedo.satmesh.proto.TextMessage;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.MessageRepository;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;
import org.sedo.satmesh.ui.data.SignalKeyExchangeStateRepository;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyBundle;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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

	/**
	 * Minimum delay, in millisecond, to be elapsed before accept new request of
	 * sending PreKeyBundle when there is an old
	 */
	public static final long DEBOUNCE_TIME_MS = 90L * 24 * 60 * 60 * 1000; // 90 days
	private static final String TAG = "NearbySignalMessenger";
	private static volatile NearbySignalMessenger INSTANCE;

	private final SignalManager signalManager;
	private final NearbyManager nearbyManager;
	private final NearbyRouteManager nearbyRouteManager;
	private final Node hostNode; // Represents our own device
	private final MessageRepository messageRepository;
	private final NodeRepository nodeRepository;
	private final SignalKeyExchangeStateRepository keyExchangeStateRepository;
	private final ExecutorService executor;

	/**
	 * Private constructor to enforce Singleton pattern.
	 * Takes all necessary dependencies.
	 */
	private NearbySignalMessenger(
			@NonNull Context context,
			NearbyManager nearbyManager,
			SignalManager signalManager,
			Node hostNode) {
		this.nearbyManager = nearbyManager;
		this.signalManager = signalManager;
		this.hostNode = hostNode;
		messageRepository = new MessageRepository(context);
		nodeRepository = new NodeRepository(context);
		keyExchangeStateRepository = new SignalKeyExchangeStateRepository(context);
		this.executor = Executors.newSingleThreadExecutor(); // Single thread for ordered message processing
		this.nearbyRouteManager = new NearbyRouteManager(nearbyManager, signalManager, context, executor);
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
	 * @param context       the application context
	 * @param nearbyManager The NearbyManager instance. Required for the first initialization.
	 * @param signalManager The SignalManager instance. Required for the first initialization.
	 * @param hostNode      The host Node instance. Required for the first initialization.
	 * @return The singleton instance of NearbySignalMessenger.
	 * @throws IllegalStateException if called without all required dependencies when the instance
	 *                               has not yet been created.
	 */
	public static NearbySignalMessenger getInstance(
			@NonNull Context context,
			NearbyManager nearbyManager,
			SignalManager signalManager,
			Node hostNode) {
		if (INSTANCE == null) {
			synchronized (NearbySignalMessenger.class) {
				if (INSTANCE == null) {
					if (nearbyManager == null || signalManager == null || hostNode == null) {
						throw new IllegalStateException("NearbySignalMessenger.getInstance() called for the first time without all required dependencies.");
					}
					INSTANCE = new NearbySignalMessenger(context, nearbyManager, signalManager, hostNode);
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
	public void onConnectionInitiated(@NonNull String endpointId, @NonNull String deviceAddressName) {
		Log.d(TAG, "Connection initiated with: " + deviceAddressName + " (EndpointId: " + endpointId + ")");
		// When a connection is initiated, we should accept it.
		// The key exchange logic will happen once connected or upon first message.
		nearbyManager.acceptConnection(endpointId);
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
				Node node = nodeRepository.findNodeSync(deviceAddressName);
				if (node != null) {
					node.setLastSeen(System.currentTimeMillis());
					node.setConnected(true);
					nodeRepository.update(node);
					Log.d(TAG, "Node " + deviceAddressName + " marked as connected in DB.");
				} else {
					// Create a new node entity if it doesn't exist.
					// This covers cases where a device is connected but not yet explicitly "discovered"
					// or persisted from a previous run.
					Node newNode = new Node();
					newNode.setAddressName(deviceAddressName);
					newNode.setLastSeen(System.currentTimeMillis());
					newNode.setConnected(true);
					nodeRepository.insert(newNode);
					Log.d(TAG, "New Node " + deviceAddressName + " inserted and marked as connected in DB.");
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating/inserting node status on device connected: " + e.getMessage(), e);
			}
		});
	}

	// This method is called when Nearby Connections fails to establish a connection.
	@Override
	public void onConnectionFailed(String deviceAddressName, Status status) {
		Log.e(TAG, "Connection failed for " + deviceAddressName + " with status: " + status);
		executor.execute(() -> {
			try {
				Node node = nodeRepository.findNodeSync(deviceAddressName);
				if (node != null) {
					node.setConnected(false); // Mark as disconnected
					nodeRepository.update(node);
					Log.d(TAG, "Node " + deviceAddressName + " marked as disconnected in DB on connection failed.");
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating node status on connection failed: " + e.getMessage(), e);
			}
		});
		//messengerCallbacks.forEach(cb -> cb.onConnectionFailure(deviceAddressName, status));
	}

	// This method is called when Nearby Connections detects a disconnection.
	@Override
	public void onDeviceDisconnected(@NonNull String endpointId, @NonNull String deviceAddressName) {
		Log.d(TAG, "Device disconnected: " + deviceAddressName + " (EndpointId: " + endpointId + ")");
		executor.execute(() -> {
			try {
				Node node = nodeRepository.findNodeSync(deviceAddressName);
				if (node != null) {
					node.setConnected(false); // Mark as disconnected
					nodeRepository.update(node);
					Log.d(TAG, "Node " + deviceAddressName + " marked as disconnected in DB.");
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating node status on device disconnected: " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Handles the event when a NEW Nearby endpoint is found.
	 * Decides whether to initiate a connection based on the current connection state.
	 *
	 * @param endpointId   The ID of the discovered endpoint.
	 * @param endpointName The Signal Protocol address name of the discovered endpoint.
	 */
	public void onEndpointFound(@NonNull String endpointId, @NonNull String endpointName) {
		Log.i(TAG, "Attempting to request connection to " + endpointName + " (ID: " + endpointId + ")");
		/*
		 * The actual connection success/failure will be handled by
		 * the onConnectionResult callback in NearbyManager's ConnectionLifecycleCallback.
		 */
		nearbyManager.requestConnection(endpointId, endpointName);
	}

	/**
	 * Handles the event when a Nearby endpoint is lost (goes out of range).
	 * This method primarily serves for logging and potentially notifying other components,
	 * as NearbyManager's own onDisconnected callback handles active connections.
	 *
	 * @param endpointId   The ID of the lost endpoint.
	 * @param endpointName The Signal Protocol address name of the lost endpoint.
	 */
	public void onEndpointLost(@NonNull String endpointId, @NonNull String endpointName) {
		Log.d(TAG, "handleEndpointLost: Lost " + endpointName + " (ID: " + endpointId + ")");
		/*
		 * This method is primarily for notification and logging that a discovered endpoint is no
		 * longer available. The NearbyManager's internal mechanisms.
		 * But, we need to set the node connected state to false
		 */
		executor.execute(() -> {
			Node remote = nodeRepository.findNodeSync(endpointName);
			if (remote == null) {
				Log.w(TAG, "onEndpointLost: unable to locate the lost node.");
				return;
			}
			remote.setConnected(false);
			nodeRepository.update(remote);
		});
	}

	// Implementation of `NearbyManager.PayloadListener`
	@Override
	public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
		executor.execute(() -> handleReceivedNearbyMessage(endpointId, payload));
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
		NodeTransientStateRepository.getInstance().clearTransientStates().shutdown();
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
	protected void sendNearbyMessageInternal(@NonNull NearbyMessage nearbyMessage,
	                                         @NonNull String recipientAddressName,
	                                         @NonNull BiConsumer<Payload, Boolean> callback) {
		nearbyManager.sendNearbyMessageInternal(nearbyMessage, recipientAddressName, callback);
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
				Message msg = messageRepository.getMessageById(messageDbId);
				if (msg != null) {
					msg.setStatus(status);
					if (payloadId != null) {
						msg.setPayloadId(payloadId); // Set the Nearby Payload ID
					}
					messageRepository.updateMessage(msg);
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
	 * Delegated method for {@link SignalManager#hasSession(SignalProtocolAddress)}
	 */
	public boolean hasSession(@NonNull String deviceAddressName) {
		return signalManager.hasSession(getAddress(deviceAddressName));
	}

	/**
	 * Handles the initial key exchange with a remote device.
	 * This is called when a message needs to be sent but no active Signal session exists.
	 *
	 * @param remoteAddressName The SignalProtocolAddress name of the remote device.
	 */
	public void handleInitialKeyExchange(@NonNull String remoteAddressName) {
		// This method is called from an executor thread, so direct calls to `signalManager` are fine.
		try {
			SignalKeyExchangeState keyExchangeState;
			// Check if we have an existing Signal Protocol session for this remoteAddressName.
			// This means we are already ready to send encrypted messages to them.
			if (hasSession(remoteAddressName)) {
				Log.d(TAG, "Session already active with " + remoteAddressName);
			}

			Log.d(TAG, "handleInitialKeyExchange() from " + hostNode.getAddressName() + " to " + remoteAddressName);
			// Get the persistent key exchange state for this remote node.
			keyExchangeState = keyExchangeStateRepository.getByRemoteAddressSync(remoteAddressName);

			// Checks if there is an old sent of the PreKeyBundle to the same remote device
			if (keyExchangeState != null && keyExchangeState.getLastOurSentAttempt() != null
					&& System.currentTimeMillis() - keyExchangeState.getLastOurSentAttempt() < DEBOUNCE_TIME_MS) {
				Log.d(TAG, "You have already sent recently your PreKeyBundle to device: " + remoteAddressName);
				return;
			}
			if (keyExchangeState == null) {
				keyExchangeState = new SignalKeyExchangeState(remoteAddressName);
			}
			final SignalKeyExchangeState exchangeState = keyExchangeState;
			// Prepare packet and sent
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
					//messengerCallbacks.forEach(cb -> cb.onKeyExchangeInitiated(remoteAddressName, payload.getId()));
					// Update persistent state to reflect that our bundle was effectively 'sent'
					exchangeState.setLastOurSentAttempt(System.currentTimeMillis());
					keyExchangeStateRepository.save(exchangeState);
				} else {
					Log.e(TAG, "Failed to send key exchange message to " + remoteAddressName);
					//messengerCallbacks.forEach(cb -> cb.onKeyExchangeFailed(remoteAddressName, "Nearby send failed."));
				}
			});
		} catch (Exception e) {
			Log.e(TAG, "Error initiating key exchange with " + remoteAddressName, e);
			//messengerCallbacks.forEach(cb -> cb.onKeyExchangeFailed(remoteAddressName, "Generation/Serialization error: " + e.getMessage()));
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
				if (!hasSession(recipientAddressName)) {
					Log.i(TAG, "No active Signal session with " + recipientAddressName + ". Attempting to establish session first.");
					// Update message status to "FAILED" or "PENDING_KEY_EXCHANGE" in DB.
					updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_PENDING_KEY_EXCHANGE, null);
					/*
					 * Initiate key exchange if no session. This is a crucial change.
					 * The actual message will be sent *after* session is established.
					 * This assumes the UI will re-attempt sending once session is ready, or queue it.
					 * For robustness, we could queue the message here, but for now, we just log and return.
					 */
					handleInitialKeyExchange(recipientAddressName);
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
					} else {
						Log.e(TAG, "Failed to send TextMessage to " + recipientAddressName + " (Payload ID: " + (payload != null ? payload.getId() : "N/A") + ")");
						// Update message in DB with failed status
						updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, null);
					}
				});

			} catch (NoSessionException e) {
				// This case should ideally be caught by hasActiveSession() check, but good for robustness
				Log.e(TAG, "No Signal session with " + recipientAddressName + " to encrypt message. Error: " + e.getMessage());
				updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, null);
				//messengerCallbacks.forEach(cb -> cb.onSendMessageFailed(recipientAddressName, "No Signal session for encryption."));
			} catch (Exception e) {
				Log.e(TAG, "Error encrypting or sending TextMessage to " + recipientAddressName, e);
				updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, null);
				//messengerCallbacks.forEach(cb -> cb.onSendMessageFailed(recipientAddressName, "Encryption error: " + e.getMessage()));
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
				if (!hasSession(recipientAddressName)) {
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
				if (!hasSession(recipientAddressName)) {
					Log.w(TAG, "No active Signal session with " + recipientAddressName + " to send PersonalInfo. Skipping.");
					return;
				}

				NearbyMessageBody messageBody = NearbyMessageBody.newBuilder()
						.setMessageType(NearbyMessageBody.MessageType.PERSONAL_INFO)
						.setEncryptedData(info.toByteString())
						.build();

				CiphertextMessage cipherMessage = signalManager.encryptMessage(recipientAddress, messageBody.toByteArray());
				byte[] cipherData = cipherMessage.serialize();
				//android.util.Base64.encodeToString(cipherData, android.util.Base64.DEFAULT)
				NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
						.setExchange(false)
						.setBody(ByteString.copyFrom(cipherData))
						.build();

				sendNearbyMessageInternal(nearbyMessage, recipientAddressName, (payload, success) -> {
					if (success) {
						Log.d(TAG, "PersonalInfo sent successfully to " + recipientAddressName);
					} else {
						Log.e(TAG, "Failed to send PersonalInfo to " + recipientAddressName);
					}
				});

			} catch (Exception e) {
				Log.e(TAG, "Error sending PersonalInfo to " + recipientAddressName, e);
			}
		});
	}

	/**
	 * Attempts to resend messages that previously failed to send to the
	 * remote node and tries to send delivery ack for messages the previous attempt failed.
	 * This method should be called when a secure session is established.
	 *
	 * @param remoteNode the remote node
	 */
	public void attemptResendFailedMessagesTo(@NonNull Node remoteNode) {
		executor.execute(() -> {
			// Retrieve messages with MESSAGE_STATUS_FAILED or MESSAGE_STATUS_PENDING for the current remote node
			List<Message> failedMessages = messageRepository.getMessagesInStatusesForRecipientSync(
					remoteNode.getId(),
					Arrays.asList(Message.MESSAGE_STATUS_FAILED,
							Message.MESSAGE_STATUS_PENDING /* Occurs when the send failure mapping failed*/
					));

			if (failedMessages != null && !failedMessages.isEmpty()) {
				Log.d(TAG, "Found " + failedMessages.size() + " failed messages to resend for " + remoteNode.getDisplayName());
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
					sendEncryptedTextMessage(remoteNode.getAddressName(), text, message.getId());
				}
			} else {
				Log.d(TAG, "No failed messages found to resend for " + remoteNode.getDisplayName());
			}

			// Retrieve message for which sending message delivery ack failed
			List<Message> missingAck = messageRepository.getMessagesInStatusesFromSenderSync(remoteNode.getId(), Collections.singletonList(Message.MESSAGE_STATUS_PENDING));
			if (missingAck != null && !missingAck.isEmpty()) {
				for (Message message : missingAck) {
					sendMessageAck(message.getPayloadId(), remoteNode.getAddressName(), true, success -> {
						if (success) {
							message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
							messageRepository.updateMessage(message);
						}
					});
				}
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
				return;
			}

			Log.d(TAG, "Received bytes (Payload ID: " + payload.getId() + ", Size: " + data.length + ") from " + endpointId);
			NearbyMessage nearbyMessage = NearbyMessage.parseFrom(data);
			String senderAddressName = nearbyManager.getEndpointName(endpointId); // Get Signal address name

			if (senderAddressName == null) {
				Log.e(TAG, "Sender address name is null for endpoint " + endpointId + ". Cannot process message.");
				return;
			}

			// Update node's last seen and connected status in DB
			Node node = nodeRepository.findNodeSync(senderAddressName);
			if (node != null) {
				node.setLastSeen(System.currentTimeMillis());
				nodeRepository.update(node);
			} else {
				// Insert new node if not found
				Node newNode = new Node();
				newNode.setAddressName(senderAddressName);
				newNode.setLastSeen(System.currentTimeMillis());
				nodeRepository.insert(newNode);
				Log.d(TAG, "New node " + senderAddressName + " inserted in DB upon message receipt.");
			}


			if (nearbyMessage.getExchange()) {
				// It's a key exchange message
				if (!nearbyMessage.hasKeyExchangeMessage()) {
					Log.e(TAG, "Received NearbyMessage with exchange=true but no key_exchange_message content from " + senderAddressName);
					return;
				}
				handleReceivedKeyExchange(senderAddressName, nearbyMessage.getKeyExchangeMessage().getPreKeyBundle().toByteArray());
			} else {
				// It's an encrypted message body
				if (!nearbyMessage.hasBody()) {
					Log.e(TAG, "Received NearbyMessage with exchange=false but no body content from " + senderAddressName);
					return;
				}
				handleReceivedEncryptedMessage(senderAddressName, nearbyMessage.getBody().toByteArray(), payload.getId());
			}
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse NearbyMessage from " + endpointId + ": " + e.getMessage(), e);
		} catch (Exception e) {
			Log.e(TAG, "Unexpected error handling received NearbyMessage from " + endpointId + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Handles a received PreKeyBundle from a remote device, establishing or updating a Signal session.
	 * This runs on the caller thread
	 *
	 * @param senderAddressName The SignalProtocolAddress name of the sender.
	 * @param preKeyBundleData  The serialized PreKeyBundle.
	 */
	private void handleReceivedKeyExchange(@NonNull String senderAddressName, @NonNull byte[] preKeyBundleData) {
		boolean requireResponse = false;
		try {
			Log.d(TAG, "Received Key Exchange from: " + senderAddressName);
			SignalProtocolAddress remoteAddress = getAddress(senderAddressName);
			SignalKeyExchangeState exchangeState = keyExchangeStateRepository.getByRemoteAddressSync(senderAddressName);
			if (exchangeState == null) {
				exchangeState = new SignalKeyExchangeState(senderAddressName);
			}
			exchangeState.setLastTheirReceivedAttempt(System.currentTimeMillis());

			// Process the received PreKeyBundle using SignalManager
			// The method establishSessionFromRemotePreKeyBundle handles session establishment/updates
			signalManager.establishSessionFromRemotePreKeyBundle(remoteAddress, signalManager.deserializePreKeyBundle(preKeyBundleData));

			Log.d(TAG, "Signal session established/updated with " + senderAddressName + ". Endpoint mapped.");
			keyExchangeStateRepository.save(exchangeState);
			requireResponse = exchangeState.getLastOurSentAttempt() == null;
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to deserialize PreKeyBundle from " + senderAddressName + ": " + e.getMessage(), e);
		} catch (InvalidKeyException e) {
			Log.e(TAG, "Invalid key in PreKeyBundle from " + senderAddressName + ": " + e.getMessage(), e);
		} catch (Exception e) {
			Log.e(TAG, "Error processing key exchange from " + senderAddressName + ": " + e.getMessage(), e);
		}
		// I use two blocks try-catch to ensure there is no conflict about exception throwing
		try {
			// Update node status in DB
			Node node = nodeRepository.findNodeSync(senderAddressName);
			if (node != null) {
				node.setLastSeen(System.currentTimeMillis());
				nodeRepository.update(node);
				Log.d(TAG, "Node " + senderAddressName + " updated with hasSignalSession=true.");
			} else {
				Log.e(TAG, "Node " + senderAddressName + " not found in DB after key exchange. This should not happen.");
			}
		} catch (Exception e) {
			Log.e(TAG, "Error updating Node DB status for " + senderAddressName + ": " + e.getMessage(), e);
		}
		/*
		 * Proactive Step: Check if we need to send our PreKeyBundle back
		 * We need to send our bundle if they haven't received it yet (i.e., no session for them exists on our side).
		 * The 'hasSession' method checks if WE (local device) have a session to SEND to THEM (the sender).
		 * If we DON'T have a session for THEM, it means they likely haven't received our PreKeyBundle yet,
		 * or their existing session has expired/been reset.
		 */
		if (requireResponse) {
			Log.d(TAG, "Proactively sending our PreKeyBundle to " + senderAddressName + " in response.");
			/*
			 * We trigger the initial key exchange process from our side for this sender.
			 * This will send our PreKeyBundle to them and establish OUR session to send messages to THEM.
			 * It's important to use handleInitialKeyExchange as it encapsulates the logic
			 * for generating/sending our bundle.
			 */
			handleInitialKeyExchange(senderAddressName);
		}
	}

	/**
	 * Handles an incoming encrypted message payload.
	 * It decrypts the message, parses its body, and processes it based on its type.
	 *
	 * @param senderAddressName The SignalProtocolAddress.name of the sender.
	 * @param cipherData        The raw encrypted bytes of the CiphertextMessage (which contains NearbyMessageBody).
	 * @param payloadId         The Nearby Payload ID.
	 */
	private void handleReceivedEncryptedMessage(@NonNull String senderAddressName, @NonNull byte[] cipherData, long payloadId) {
		try {
			Log.d(TAG, "Received Encrypted Message from: " + senderAddressName + ", Size: " + cipherData.length);

			CiphertextMessage receivedCipherMessage;
			/*
			 * Reconstruct CiphertextMessage from raw bytes
			 * Note: We need to properly determine if it's PREKEY_TYPE or WHISPER_TYPE.
			 */
			try {
				receivedCipherMessage = new SignalMessage(cipherData);
			} catch (Exception ignored) {
				/*
				 * Instruction in the try clause will throw exception if the message
				 * is the first encrypted through devices
				 */
				receivedCipherMessage = new PreKeySignalMessage(cipherData);
			}

			SignalProtocolAddress senderAddress = getAddress(senderAddressName);
			byte[] decryptedBytes = signalManager.decryptMessage(senderAddress, receivedCipherMessage);
			NearbyMessageBody decryptedMessageBody = NearbyMessageBody.parseFrom(decryptedBytes);

			// Now, we have the decrypted content (NearbyMessageBody).
			// We need to persist the received message if it's a TextMessage.
			// For ACKs or Info, we update existing entries or just notify.

			parseDecryptedMessage(decryptedMessageBody, senderAddressName, payloadId);
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse decrypted message body from " + senderAddressName + ": " + e.getMessage(), e);
		} catch (NoSessionException e) {
			Log.e(TAG, "No Signal session to decrypt message from " + senderAddressName + ": " + e.getMessage(), e);
			/*
			 * This could happen if sender sends message before session is fully established, or if our session was lost.
			 * We should re-initiate key exchange if possible, or request sender to re-send.
			 * Optionally, try to initiate key exchange again to fix session
			 */
			executor.execute(() -> handleInitialKeyExchange(senderAddressName));
		} catch (Exception e) {
			Log.e(TAG, "Error processing encrypted message from " + senderAddressName + ": " + e.getMessage(), e);
		}
	}

	// Helper method
	protected void parseDecryptedMessage(@NonNull NearbyMessageBody decryptedMessageBody, @NonNull String senderAddressName, long payloadId) throws Exception {
		SignalProtocolAddress senderAddress = getAddress(senderAddressName);
		Message message;
		switch (decryptedMessageBody.getMessageType()) {
			case ENCRYPTED_MESSAGE:
				TextMessage textMessage = TextMessage.parseFrom(decryptedMessageBody.getEncryptedData());
				Node senderNode = nodeRepository.findNodeSync(senderAddressName);
				if (senderNode == null) {
					Log.e(TAG, "Failed to identify, in DB, the sender node at address " + senderAddress + " msg.payloadId=" + payloadId);
					return;
				}

				Log.d(TAG, "Received and persisted TextMessage from " + senderAddressName + ": " + textMessage.getContent());
				// Persist the received text message
				message = new Message();
				if (textMessage.getPayloadId() != 0L) {
					// We are in case or retransmission
					payloadId = textMessage.getPayloadId();
				}
				message.setPayloadId(payloadId);
				message.setStatus(Message.MESSAGE_STATUS_PENDING);
				message.setSenderNodeId(senderNode.getId());
				message.setRecipientNodeId(hostNode.getId());
				message.setType(Message.MESSAGE_TYPE_TEXT);
				message.setContent(textMessage.getContent());
				message.setTimestamp(textMessage.getTimestamp());
				final long finalPayloadId = payloadId;
				messageRepository.insertMessage(message, success -> {
					if (success) {
						sendMessageAck(finalPayloadId, senderAddressName, true, newSuccess -> {
							if (newSuccess) {
								message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
								messageRepository.updateMessage(message);
							}
						});
					}
				});
				break;
			case MESSAGE_DELIVERED_ACK:
				MessageAck deliveredAck = MessageAck.parseFrom(decryptedMessageBody.getEncryptedData());
				// Update status of the original message in DB
				message = messageRepository.getMessageByPayloadId(deliveredAck.getPayloadId());
				if (message == null) {
					Log.e(TAG, "Receiving message delivered ack for non-identified payload id=" + deliveredAck.getPayloadId());
					return;
				}
				message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
				messageRepository.updateMessage(message);
				Log.d(TAG, "Received Delivered ACK for payload " + deliveredAck.getPayloadId() + " from " + senderAddressName);
				//messengerCallbacks.forEach(cb -> cb.onMessageStatusChanged(deliveredAck.getPayloadId(), true));
				break;
			case MESSAGE_READ_ACK:
				MessageAck readAck = MessageAck.parseFrom(decryptedMessageBody.getEncryptedData());
				// Update status of the original message in DB
				message = messageRepository.getMessageByPayloadId(readAck.getPayloadId());
				if (message == null) {
					Log.e(TAG, "Receiving message read ack for non-identified payload id=" + readAck.getPayloadId());
					return;
				}
				message.setStatus(Message.MESSAGE_STATUS_READ);
				messageRepository.updateMessage(message);
				Log.d(TAG, "Received Read ACK for payload " + readAck.getPayloadId() + " from " + senderAddressName);
				//messengerCallbacks.forEach(cb -> cb.onMessageStatusChanged(readAck.getPayloadId(), false));
				break;
			case PERSONAL_INFO:
				PersonalInfo personalInfo = PersonalInfo.parseFrom(decryptedMessageBody.getEncryptedData());
				// Update node's display name and potentially other info in DB
				Node nodeToUpdate = nodeRepository.findNodeSync(senderAddressName);
				if (nodeToUpdate != null) {
					nodeToUpdate.setPersonalInfo(personalInfo);
					nodeToUpdate.setLastSeen(System.currentTimeMillis()); // Update last seen as well
					nodeRepository.update(nodeToUpdate);
					Log.d(TAG, "Received and updated PersonalInfo for " + senderAddressName + ": " + personalInfo.getDisplayName());
					if (personalInfo.getExpectResult()) {
						Node hostNodeFromDb = nodeRepository.findNodeSync(hostNode.getAddressName());
						hostNode.setPersonalInfo(hostNodeFromDb.toPersonalInfo(false));
						sendPersonalInfo(hostNodeFromDb.toPersonalInfo(false), senderAddressName);
					}
				} else {
					// This case might not happen cause exchanging personal info require secured session and the session requires node persistence.
					Log.d(TAG, "Received PersonalInfo for new node " + senderAddressName + ": " + personalInfo.getDisplayName());
					return;
				}
				break;
			case TYPING_INDICATOR:
				// TypingIndicator typingIndicator = TypingIndicator.parseFrom(decryptedMessageBody.getEncryptedData());
				// Log.d(TAG, "Received TypingIndicator from " + senderAddressName + ": " + typingIndicator.getIsTyping());
				// TODO: Notify UI via NodeStateRepository to show/hide typing indicator if needed
				break;
			case ROUTE_DISCOVERY_REQ:
				Log.d(TAG, "Received ROUTE_DISCOVERY_REQ from " + senderAddressName);
				RouteRequestMessage routeRequestMessage = RouteRequestMessage.parseFrom(decryptedMessageBody.getEncryptedData());
				nearbyRouteManager.handleIncomingRouteRequest(senderAddressName, routeRequestMessage, hostNode.getAddressName());
				break;
			case ROUTE_DISCOVERY_RESP:
				Log.d(TAG, "Received ROUTE_DISCOVERY_RESP from " + senderAddressName);
				RouteResponseMessage routeResponseMessage = RouteResponseMessage.parseFrom(decryptedMessageBody.getEncryptedData());
				nearbyRouteManager.handleIncomingRouteResponse(senderAddressName, routeResponseMessage);
				break;
			case ROUTED_MESSAGE:
				RoutedMessage routedMessage = RoutedMessage.parseFrom(decryptedMessageBody.getEncryptedData());
				nearbyRouteManager.handleIncomingRoutedMessage(senderAddressName, routedMessage, hostNode.getAddressName(), payloadId);
				break;
			case UNRECOGNIZED:
			case UNKNOWN:
			default:
				Log.w(TAG, "Received UNKNOWN or unhandled message type: " + decryptedMessageBody.getMessageType() + " from " + senderAddressName);
				break;
		}
	}

	public void onRouteFound(@NonNull String destinationAddressName) {
		executor.execute(() -> {
			Log.d(TAG, "Handling route founding to destination: " + destinationAddressName);
			Node remoteNode = nodeRepository.findNodeSync(destinationAddressName);
			if (remoteNode == null) {
				Log.e(TAG, "Unable to locate the remote node at address: " + destinationAddressName);
				return;
			}
			attemptResendFailedMessagesTo(remoteNode);
		});
	}

	/**
	 * Gets a reference of the {@link NearbyRouteManager} bound to this {@code NearbySignalMessenger}
	 */
	public NearbyRouteManager getNearbyRouteManager() {
		return nearbyRouteManager;
	}
}
