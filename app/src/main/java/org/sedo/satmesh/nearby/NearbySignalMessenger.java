package org.sedo.satmesh.nearby;

import static org.sedo.satmesh.nearby.NearbyManager.DeviceConnectionListener;
import static org.sedo.satmesh.nearby.NearbyManager.PayloadListener;
import static org.sedo.satmesh.proto.NearbyMessageBody.MessageType.ENCRYPTED_MESSAGE;
import static org.sedo.satmesh.signal.SignalManager.DecryptionCallback;
import static org.sedo.satmesh.signal.SignalManager.EncryptionCallback;
import static org.sedo.satmesh.signal.SignalManager.SessionCallback;
import static org.sedo.satmesh.signal.SignalManager.getAddress;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.connection.Payload;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.sedo.satmesh.proto.MessageAck;
import org.sedo.satmesh.proto.NearbyMessage;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.PersonalInfo;
import org.sedo.satmesh.proto.PreKeyBundleExchange;
import org.sedo.satmesh.proto.TextMessage;
import org.sedo.satmesh.signal.SignalManager;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyBundle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages Nearby Connections for discovering devices, establishing connections,
 * and exchanging Signal Protocol related messages (PreKeyBundles and encrypted messages).
 * This class handles the low-level communication aspects and integrates with SignalManager
 * for cryptographic operations.
 */
public class NearbySignalMessenger {

	private static final String TAG = "NearbySignalMessenger";

	private static volatile NearbySignalMessenger INSTANCE;

	// Map to store endpoint IDs by SignalProtocolAddress name: remote device's SignalProtocolAddress name -> endpointId
	private final Map<String, String> addressToEndpointId = new HashMap<>();

	private final SignalManager signalManager;
	private final NearbyManager nearbyManager;
	private final ExecutorService executor; // For background tasks to avoid blocking UI thread
	// Listeners
	private final List<MessageReceivedListener> messageReceivedListeners = new CopyOnWriteArrayList<>(); // CopyOnWriteArrayList is thread-safe
	private final List<MessageSendingListener> messageSendingListeners = new CopyOnWriteArrayList<>();
	private final List<KeyExchangeListener> keyExchangeListeners = new CopyOnWriteArrayList<>();
	private final List<MessageDecryptionFailureListener> decryptionFailureListeners = new CopyOnWriteArrayList<>();
	private final List<PersonalInfoChangeListener> infoChangeListeners = new CopyOnWriteArrayList<>();
	private final DeviceConnectionListener connectionListener = new DeviceConnectionListener() {
		@Override
		public void onConnectionInitiated(String endpointId, String deviceAddressName) {
		}

		@Override
		public void onDeviceConnected(String endpointId, String deviceAddressName) {
			addressToEndpointId.put(deviceAddressName, endpointId);
			// Immediately initiate key exchange after connection is established
			executor.execute(() -> initiateKeyExchange(endpointId, deviceAddressName));
		}

		@Override
		public void onConnectionFailed(String deviceAddressName, Status status) {
		}

		@Override
		public void onDeviceDisconnected(String unused, String deviceAddressName) {
			addressToEndpointId.remove(deviceAddressName);
		}
	};
	private final List<SignalMessengerCallback> messengerCallbacks = new CopyOnWriteArrayList<>();
	private final PayloadListener payloadListener = new PayloadListener() {
		@Override
		public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
			executor.execute(() -> handleReceivedNearbyMessage(endpointId, payload));
		}
	};

	/**
	 * Constructor for NearbySignalMessenger.
	 *
	 * @param signalManager The SignalManager instance for cryptographic operations.
	 */
	protected NearbySignalMessenger(@NonNull SignalManager signalManager, @NonNull NearbyManager nearbyManager) {
		this.signalManager = signalManager;
		this.nearbyManager = nearbyManager;
		this.executor = Executors.newSingleThreadExecutor(); // Single thread for ordered message processing
		// NearbyManager listeners
		this.nearbyManager.addPayloadListener(payloadListener);
		this.nearbyManager.addDeviceConnectionListener(connectionListener);
	}

	public static NearbySignalMessenger getInstance(@NonNull SignalManager signalManager, @NonNull NearbyManager nearbyManager) {
		if (INSTANCE == null) {
			synchronized (NearbySignalMessenger.class) {
				if (INSTANCE == null) {
					INSTANCE = new NearbySignalMessenger(signalManager, nearbyManager);
				}
			}
		}
		return INSTANCE;
	}

	/**
	 * @param messengerCallback Used to notify caller of this method of operations which are to execute once, such as data persistence.
	 */
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
		nearbyManager.removePayloadListener(payloadListener);
		nearbyManager.removeDeviceConnectionListener(connectionListener);
	}

	public void shutdown() {
		executor.shutdown();
		clearNearbyManagerListeners();
	}

	/**
	 * Adds the listener for incoming messages.
	 *
	 * @param listener The implementation of MessageReceivedListener.
	 */
	public void addMessageReceivedListener(MessageReceivedListener listener) {
		this.messageReceivedListeners.add(listener);
	}

	/**
	 * Adds the listener for message sending to neighbor events.
	 *
	 * @param listener The implementation of {@link MessageSendingListener}.
	 */
	public void addMessageSendingListener(MessageSendingListener listener) {
		this.messageSendingListeners.add(listener);
	}

	/**
	 * Removes the listener for message sending to neighbor events.
	 *
	 * @param listener The implementation of {@link MessageSendingListener}.
	 */
	public void removeMessageSendingListener(MessageSendingListener listener) {
		this.messageSendingListeners.remove(listener);
	}

	/**
	 * Adds the listener for Signal PreKeyBundle exchange events.
	 *
	 * @param listener The implementation of {@link KeyExchangeListener}.
	 */
	public void addKeyExchangeListener(KeyExchangeListener listener) {
		this.keyExchangeListeners.add(listener);
	}

	/**
	 * Removes the listener for Signal PreKeyBundle exchange events.
	 *
	 * @param listener The implementation of {@link KeyExchangeListener}.
	 */
	public void removeKeyExchangeListener(KeyExchangeListener listener) {
		this.keyExchangeListeners.remove(listener);
	}

	/**
	 * Adds the listener for failure event on decryption of encrypted message.
	 *
	 * @param listener The implementation of {@link MessageDecryptionFailureListener}.
	 */
	public void addMessageDecryptionFailureListener(MessageDecryptionFailureListener listener) {
		this.decryptionFailureListeners.add(listener);
	}

	/**
	 * Removes the listener for failure event on decryption of encrypted message.
	 *
	 * @param listener The implementation of {@link MessageDecryptionFailureListener}.
	 */
	public void removeMessageDecryptionFailureListener(MessageDecryptionFailureListener listener) {
		this.decryptionFailureListeners.remove(listener);
	}

	/**
	 * Adds the listener for {@link PersonalInfo} change event.
	 *
	 * @param listener The implementation of {@link PersonalInfoChangeListener}.
	 */
	public void addInfoChangeListener(PersonalInfoChangeListener listener) {
		this.infoChangeListeners.add(listener);
	}

	/**
	 * Removes the listener for {@link PersonalInfo} change event.
	 *
	 * @param listener The implementation of {@link PersonalInfoChangeListener}.
	 */
	public void removeInfoChangeListener(PersonalInfoChangeListener listener) {
		this.infoChangeListeners.remove(listener);
	}

	/**
	 * Initiates the Signal Protocol key exchange with a connected endpoint.
	 * This sends the local device's PreKeyBundle to the remote device.
	 *
	 * @param endpointId        The ID of the connected endpoint.
	 * @param remoteAddressName The SignalProtocolAddress name of the remote device.
	 */
	public void initiateKeyExchange(@NonNull String endpointId, @NonNull String remoteAddressName) {
		executor.execute(() -> {
			try {
				PreKeyBundle localPreKeyBundle = signalManager.getPreKeyBundle();
				byte[] serializedPreKeyBundle = signalManager.serializePreKeyBundle(localPreKeyBundle);

				// Create Protobuf message for key exchange
				PreKeyBundleExchange preKeyBundleExchange = PreKeyBundleExchange.newBuilder()
						.setPreKeyBundle(ByteString.copyFrom(serializedPreKeyBundle))
						.build();

				// Wrap the key exchange message in a generic NearbyMessage
				NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
						.setExchange(true)
						.setKeyExchangeMessage(preKeyBundleExchange)
						.build();

				nearbyManager.sendNearbyMessage(endpointId, nearbyMessage, ((payload, success) -> {
					if (success) {
						keyExchangeListeners.forEach(l -> l.onKeyExchangeInitSuccess(remoteAddressName, payload.getId()));
					} else {
						keyExchangeListeners.forEach(l -> l.onKeyExchangeInitFailed(remoteAddressName));
					}
				}));

				Log.d(TAG, "Key exchange initiated with: " + remoteAddressName);
			} catch (Exception e) {
				Log.e(TAG, "Error initiating key exchange with " + remoteAddressName, e);
			}
		});
	}

	/**
	 * Wraps and sends an encrypted message.
	 * This method will encrypt, wrap the message body in an instance of {@code NearbyMessage} and sent it via Nearby Connections.
	 *
	 * @param recipientAddressName The Signal Protocol address name of the message sender (who will receive this ACK).
	 * @param callback             the callback method used to notify the caller of successor failure of the message. If the
	 *                             encryption fails, then the functional method if called using {@code null} as payload and
	 *                             {@code false} as operation status. If the encryption success, then the generated payload
	 *                             is passed and the success status matches sending status.
	 */
	protected void wrapAndSendEncryptedMessage(@NonNull Supplier<NearbyMessageBody> body, @NonNull String recipientAddressName, @NonNull BiConsumer<Payload, Boolean> callback) {
		final String endpointId = addressToEndpointId.get(recipientAddressName);
		if (endpointId == null) {
			Log.e(TAG, "Cannot send message: no active connection to " + recipientAddressName);
			return;
		}

		executor.execute(() -> {
			SignalProtocolAddress recipientAddress = getAddress(recipientAddressName);
			signalManager.encryptMessage(recipientAddress, body.get().toByteArray(), new EncryptionCallback() {
				@Override
				public void onSuccess(CiphertextMessage cipherMessage) {
					NearbyMessage message = NearbyMessage.newBuilder()
							.setExchange(false)
							.setBody(ByteString.copyFrom(cipherMessage.serialize())).build();
					nearbyManager.sendNearbyMessage(endpointId, message, callback);
				}

				@Override
				public void onError(Exception unused) {
					Log.e(TAG, "wrapAndSendEncryptedMessage() : Encrypting message ack failed !");
					callback.accept(null, false);
				}
			});
		});
	}

	/**
	 * Sends an encrypted message to a specific remote device, directly connected to this one.
	 * Before call this method, the caller must ensure the message is stored in database in
	 * pending state. This method will notice the messenger call back on success send of the message.
	 *
	 * @param recipientAddressName The SignalProtocolAddress name of the recipient device.
	 * @param message              The plaintext message to encrypt and send.
	 * @param messageIdentifier    used to help caller to identify the message on failure or success. The same instance is passed to methods of {@link MessageSendingListener}
	 */
	public void sendEncryptedMessage(@NonNull String recipientAddressName, @NonNull TextMessage message, @NonNull Object messageIdentifier) {
		/*
		 * Important : Cause the message must be wrapped in the payload, it
		 * is not possible to write the payload ID in the message. But after creating
		 * the payload, the local message will be persisted with the correct payload
		 * ID. Thus, method of handling the message receive MUST TAKE CARE TO WRITE/SAVE
		 * THE CORRECT PAYLOAD ID.
		 */
		//The message is assumed stored in DB in state `Pending` before this method call
		wrapAndSendEncryptedMessage(() -> NearbyMessageBody.newBuilder()
				.setMessageType(ENCRYPTED_MESSAGE)
				.setEncryptedData(message.toByteString())
				.build(), recipientAddressName, (payload, success) -> {
			if (success) {
				// Send succeed
				messengerCallbacks.forEach(cb -> cb.onPayloadSentConfirmation(recipientAddressName, payload));
				messageSendingListeners.forEach(l -> l.onSendSucceed(recipientAddressName, messageIdentifier, payload.getId()));
			} else {
				// sending fails
				if (payload == null) {
					Log.w(TAG, "sendEncryptedMessage() : Message encryption failed!");
				}
				messageSendingListeners.forEach(l -> l.onSendFailed(recipientAddressName, messageIdentifier));
			}
		});
	}

	/**
	 * Sends a message acknowledgement (ACK) to the recipient.
	 * This method will encrypt the ACK and sent it via Nearby Connections.
	 *
	 * @param payloadId            The original message's payload ID that is being acknowledged.
	 * @param recipientAddressName The Signal Protocol address name of the message sender (who will receive this ACK).
	 * @param delivered            Use {@code true} to send delivery ack, {@code false} to send message read ack.
	 * @param callback             the callback method used to notify the caller of successor failure of the message sending ack
	 */
	public void sendMessageAck(long payloadId, @NonNull String recipientAddressName, boolean delivered, @NonNull Consumer<Boolean> callback) {
		wrapAndSendEncryptedMessage(() ->
						NearbyMessageBody.newBuilder()
								.setMessageType(delivered ? NearbyMessageBody.MessageType.MESSAGE_DELIVERED_ACK : NearbyMessageBody.MessageType.MESSAGE_READ_ACK)
								.setEncryptedData(
										MessageAck.newBuilder()
												.setPayloadId(payloadId)
												.build().toByteString()
								).build(), recipientAddressName,
				(payload, success) -> {
					if (payload == null) { // Encryption failed
						/*
						 * Until we integrated to save the time at which the message is read, we don't need to report
						 * this error. We will just expect next time, probably when user open the discussion next time,
						 * to re-send the message read ack.
						 */
						return;
					}
					callback.accept(success);
				});
	}

	//
	public void sendPersonalInfo(@NonNull PersonalInfo info, @NonNull String recipientAddressName) {
		wrapAndSendEncryptedMessage(() ->
				NearbyMessageBody.newBuilder()
						.setMessageType(NearbyMessageBody.MessageType.PERSONAL_INFO)
						.setEncryptedData(info.toByteString())
						.build(), recipientAddressName, (payload, success) -> {
			if (success) {
				infoChangeListeners.forEach(l -> l.onInfoSendingSucceed(recipientAddressName));
			} else {
				infoChangeListeners.forEach(l -> l.onInfoSendingFailed(recipientAddressName));
			}
		});
	}

	/**
	 * Handles a received NearbyMessage payload, identifying its type (key exchange or encrypted message)
	 * and processing it accordingly.
	 *
	 * @param endpointId The ID of the endpoint from which the message was received.
	 * @param payload    The payload.
	 */
	private void handleReceivedNearbyMessage(String endpointId, Payload payload) {
		try {
			// Actually, only bytes messages are supported
			byte[] data = payload.asBytes();
			NearbyMessage nearbyMessage = NearbyMessage.parseFrom(data);
			String senderAddressName = nearbyManager.getEndpointName(endpointId);

			if (nearbyMessage.getExchange()) {
				handleReceivedKeyExchange(endpointId, senderAddressName, nearbyMessage.getKeyExchangeMessage().getPreKeyBundle().toByteArray(), payload.getId());
			} else {
				// We are handling encrypted message
				handleReceivedEncryptedMessage(endpointId, senderAddressName, nearbyMessage.getBody().toByteArray(), payload.getId());
			}
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse NearbyMessage from " + endpointId + ": " + e.getMessage());
		} catch (Exception e) {
			Log.e(TAG, "Error handling received message from " + endpointId + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Handles a received PreKeyBundle from a remote device.
	 * This processes the bundle to establish a Signal session.
	 *
	 * @param endpointId        The ID of the endpoint that sent the bundle.
	 * @param senderAddressName The SignalProtocolAddress name of the sender.
	 * @param preKeyBundleData  The serialized PreKeyBundle.
	 */
	private void handleReceivedKeyExchange(String endpointId, String senderAddressName, byte[] preKeyBundleData, long payloadId) {
		executor.execute(() -> {
			try {
				Log.d(TAG, "Received Key Exchange from: " + senderAddressName + " (" + endpointId + ")");
				PreKeyBundle remotePreKeyBundle = signalManager.deserializePreKeyBundle(preKeyBundleData);
				SignalProtocolAddress remoteAddress = getAddress(senderAddressName);
				keyExchangeListeners.forEach(l -> l.onReadyToInitiateSession(remotePreKeyBundle, senderAddressName, payloadId));

				// Initiate the session with the received PreKeyBundle
				signalManager.initiateSession(remoteAddress, remotePreKeyBundle, new SessionCallback() {
					@Override
					public void onSuccess() {
						Log.d(TAG, "Session established with " + senderAddressName);
						/*
						 * Now that the session is established, map the endpointId to the actual remote address
						 * This is crucial because `onConnectionResult` only knows the endpointName, not the
						 * actual SignalProtocolAddress until key exchange is complete.
						 */
						addressToEndpointId.put(senderAddressName, endpointId);
						keyExchangeListeners.forEach(l -> l.onSessionInitSuccess(senderAddressName));
					}

					@Override
					public void onError(Exception e) {
						Log.e(TAG, "Failed to establish session with " + senderAddressName + ": " + e.getMessage(), e);
						keyExchangeListeners.forEach(l -> l.onSessionInitFailed(senderAddressName, e));
					}
				});
			} catch (InvalidProtocolBufferException e) {
				Log.e(TAG, "Failed to deserialize PreKeyBundle from " + senderAddressName + ": " + e.getMessage());
			} catch (InvalidKeyException e) {
				Log.e(TAG, "Invalid key in PreKeyBundle from " + senderAddressName + ": " + e.getMessage());
			} catch (Exception e) {
				Log.e(TAG, "Error processing key exchange from " + senderAddressName + ": " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Handles an incoming encrypted message payload, which could be a PreKeySignalMessage
	 * (first message to establish a session) or a regular SignalMessage.
	 *
	 * @param endpointId        The ID of the Nearby endpoint.
	 * @param senderAddressName The SignalProtocolAddress.name of the sender.
	 * @param cipherData        The raw encrypted bytes of the CiphertextMessage. It's an encryption of {@link NearbyMessageBody}.
	 * @param payloadId         The Nearby Payload ID (can be used for ACKs later).
	 */
	private void handleReceivedEncryptedMessage(String endpointId, String senderAddressName, byte[] cipherData, long payloadId) {
		executor.execute(() -> {
			try {
				Log.d(TAG, "Received Encrypted Message from: " + senderAddressName + " (" + endpointId + ")");

				CiphertextMessage receivedCipherMessage = new SignalMessage(cipherData);

				SignalProtocolAddress senderAddress = getAddress(senderAddressName);
				executor.execute(() -> signalManager.decryptMessage(senderAddress, receivedCipherMessage, new DecryptionCallback() {
					@Override
					public void onSuccess(byte[] decryptedBytes) {
						try {
							NearbyMessageBody decryptedMessageBody = NearbyMessageBody.parseFrom(decryptedBytes);

							// Process the content based on its type
							switch (decryptedMessageBody.getMessageType()) {
								case ENCRYPTED_MESSAGE:
									/*
									 * This contains the actual TextMessage.
									 * The payload ID is not defined in the text message, let's define it.
									 */
									TextMessage textMessage = TextMessage.parseFrom(decryptedMessageBody.getEncryptedData())
											.toBuilder()
											.setPayloadId(payloadId)
											.build();
									messengerCallbacks.forEach(cb -> cb.onTextMessageReceived(textMessage, senderAddressName));
									messageReceivedListeners.forEach(l -> l.onMessageReceived(senderAddressName, textMessage));
									break;
								case MESSAGE_DELIVERED_ACK:
									MessageAck deliveredAck = MessageAck.parseFrom(decryptedMessageBody.getEncryptedData());
									messengerCallbacks.forEach(cb -> cb.onMessageStatusChanged(deliveredAck.getPayloadId(), true));
									break;
								case MESSAGE_READ_ACK:
									MessageAck readAck = MessageAck.parseFrom(decryptedMessageBody.getEncryptedData());
									messengerCallbacks.forEach(cb -> cb.onMessageStatusChanged(readAck.getPayloadId(), false));
									break;
								case PERSONAL_INFO:
								case CONTACT_UPDATE_INFO:
									PersonalInfo personalInfo = PersonalInfo.parseFrom(decryptedMessageBody.getEncryptedData());
									messengerCallbacks.forEach(cb -> cb.onPersonalInfoReceived(personalInfo));
									// Notify mainly the UI
									infoChangeListeners.forEach(l -> l.onInfoReceived(personalInfo));
									break;
								case TYPING_INDICATOR:
									/*
									 * TypingIndicator typingIndicator = TypingIndicator.parseFrom(decryptedMessageBody.getEncryptedData());
									 * Log.d(TAG, "Received TypingIndicator from " + senderAddressName + ": " + typingIndicator.getIsTyping());
									 * TODO: Notify UI to show/hide typing indicator
									 */
									break;
								case ROUTE_DISCOVERY:
									// This will be handled later when you implement routing logic
									// The encryptedData here would contain your RouteDiscovery proto
									Log.d(TAG, "Received ROUTE_DISCOVERY (to be implemented) from " + senderAddressName);
									break;
								case UNKNOWN:
								default:
									Log.w(TAG, "Received UNKNOWN or unhandled message type: " + decryptedMessageBody.getMessageType() + " from " + senderAddressName);
									break;
							}
						} catch (InvalidProtocolBufferException e) {
							throw new RuntimeException(e);
						}
					}

					@Override
					public void onError(Exception e) {
						decryptionFailureListeners.forEach(l -> l.onFailure(endpointId, senderAddressName, payloadId, e));
					}
				}));
			} catch (Exception e) {
				Log.e(TAG, "Error processing encrypted message from " + senderAddressName + ": " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Signal Messenger Callback for core internal operations like data persistence and network acknowledgements.
	 * This should typically be implemented by a Repository or a central data management layer.
	 */
	public interface SignalMessengerCallback {
		/**
		 * Persists the given TextMessage to local storage and handles sending (or requesting for) delivered acknowledgement.
		 *
		 * @param message           The TextMessage to persist. It encapsulate the payload ID
		 * @param senderAddressName The Signal address name of the sender.
		 */
		void onTextMessageReceived(@NonNull TextMessage message, @NonNull String senderAddressName);

		/**
		 * Marks the message bound to the payload ID as delivered or read in local storage.
		 *
		 * @param payloadId The payload ID of the message to mark.
		 * @param delivered Use {@code true} to mark the message as delivered,
		 *                  and {@code false} to mark the message as read.
		 */
		void onMessageStatusChanged(long payloadId, boolean delivered);

		/**
		 * Processes received PersonalInfo. This method is responsible for evaluating
		 * {@link PersonalInfo#getExpectResult()} to determine if a response should be sent back to the sender,
		 * and if so, the implementer must initiate that response.
		 *
		 * @param info Wrapper of personal info received from the sender.
		 */
		void onPersonalInfoReceived(@NonNull PersonalInfo info);

		/**
		 * Called when a payload has been successfully sent over the network to a neighbor.
		 * This method is responsible for updating the local message with the payload ID
		 * and potentially marking it as delivered at the network level.
		 *
		 * @param recipientAddressName The Signal address name of the recipient.
		 * @param payload              The payload that was successfully sent.
		 */
		void onPayloadSentConfirmation(@NonNull String recipientAddressName, @NonNull Payload payload);
	}

	/**
	 * Listener for message sending
	 */
	public interface MessageSendingListener {
		/**
		 * Called when the payload of the message was successfully sent to the neighbor.
		 * Usage of this method is mainly to notify the UI about messages display update.
		 *
		 * @param recipientAddressName the Signal address name of the recipient
		 * @param messageIdentifier    used to identify the message, on UI side
		 * @param payloadId            The payload ID of the wrapper of this message on sending
		 */
		void onSendSucceed(@NonNull String recipientAddressName, @NonNull Object messageIdentifier, long payloadId);

		/**
		 * Called when the plaintext message sending operation fails.
		 * Use this to mark the local message as 'failed' in UI.
		 *
		 * @param recipientAddressName The Signal address name of the recipient.
		 * @param messageIdentifier    used to identify the message, on UI side
		 */
		void onSendFailed(@NonNull String recipientAddressName, @NonNull Object messageIdentifier);
	}

	// Callbacks for UI/application layer to react to events
	public interface MessageReceivedListener {
		void onMessageReceived(String senderAddress, TextMessage message);
	}

	/**
	 * Key exchange listener
	 */
	public interface KeyExchangeListener {

		/**
		 * Alert of key exchange initialization with success
		 */
		void onKeyExchangeInitSuccess(@NonNull String remoteAddressName, long payloadId);

		/**
		 * Alert of key exchange initialization failing
		 */
		void onKeyExchangeInitFailed(@NonNull String remoteAddressName);

		/**
		 * Called when receiving PreKeyBundle and the next instruction will be initiating the Signal' session
		 */
		void onReadyToInitiateSession(PreKeyBundle preKeyBundle, String remoteAddressName, long payloadId);

		/**
		 * Called when a Signal session has been successfully initiated with a remote device.
		 *
		 * @param remoteAddressName The Signal address name of the remote device.
		 */
		void onSessionInitSuccess(String remoteAddressName);

		/**
		 * Called when Signal session initiation fails with a remote device.
		 *
		 * @param remoteAddressName The Signal address name of the remote device.
		 * @param e                 The exception that caused the failure.
		 */
		void onSessionInitFailed(String remoteAddressName, Exception e);
	}

	/**
	 * Encrypted message decryption failure listener
	 */
	public interface MessageDecryptionFailureListener {
		/**
		 * Called when an encrypted message fails to decrypt.
		 *
		 * @param senderEndpointId  The endpoint ID of the sender.
		 * @param senderAddressName The Signal address name of the sender.
		 * @param payloadId         The ID of the payload that failed to decrypt.
		 * @param e                 The exception that caused the decryption failure.
		 */
		void onFailure(String senderEndpointId, String senderAddressName, long payloadId, Exception e);
	}

	/**
	 * Listener for changes on a node's personal info.
	 */
	public interface PersonalInfoChangeListener {
		/**
		 * Called when a node's personal information changes.
		 *
		 * @param info The updated PersonalInfo object.
		 */
		void onInfoReceived(@NonNull PersonalInfo info);

		void onInfoSendingSucceed(@NonNull String remoteAddress);

		void onInfoSendingFailed(@NonNull String remoteAddress);
	}
}
