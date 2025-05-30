package org.sedo.satmesh.nearby;

import static org.sedo.satmesh.signal.SignalManager.DecryptionCallback;
import static org.sedo.satmesh.signal.SignalManager.EncryptionCallback;
import static org.sedo.satmesh.signal.SignalManager.SessionCallback;
import static org.sedo.satmesh.signal.SignalManager.getAddress;
import static org.sedo.satmesh.proto.NearbyMessageBody.MessageType.ENCRYPTED_MESSAGE;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * Manages Nearby Connections for discovering devices, establishing connections,
 * and exchanging Signal Protocol related messages (PreKeyBundles and encrypted messages).
 * This class handles the low-level communication aspects and integrates with SignalManager
 * for cryptographic operations.
 */
public class NearbySignalMessenger {

	private static final String TAG = "NearbySignalMessenger";
	private static final String SERVICE_ID = "org.sedo.tampon.SECURE_MESSENGER"; // Unique service ID for the app
	private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

	// Map to store connected endpoints: endpointId -> remote device's SignalProtocolAddress name
	private final Map<String, String> connectedEndpointAddresses = new HashMap<>();
	// Map to store endpoint IDs by SignalProtocolAddress name: remote device's SignalProtocolAddress name -> endpointId
	private final Map<String, String> addressToEndpointId = new HashMap<>();

	// Map to store pending endpoints: endpointId -> remote device's SignalProtocolAddress name
	private final Map<String, String> pendingEndpointAddresses = new HashMap<>();

	private final ConnectionsClient connectionsClient;
	private final SignalManager signalManager;
	private final Executor executor; // For background tasks to avoid blocking UI thread
	private final SignalMessengerCallback messengerCallback;

	/**
	 * True if we are advertising.
	 */
	private boolean isAdvertising = false;

	/**
	 * True if we are discovering.
	 */
	private final boolean isDiscovering = false;
	/**
	 * True if we are asking a discovered device to connect to us. While we ask, we cannot ask another
	 * device.
	 */
	private final boolean isConnecting = false;

	// Listeners
	private final List<MessageReceivedListener> messageReceivedListeners = new ArrayList<>();
	private final List<DeviceConnectionListener> deviceConnectionListeners = new ArrayList<>();
	private final List<MessageSendingListener> messageSendingListeners = new ArrayList<>();
	private final List<AdvertisingListener> advertisingListeners = new ArrayList<>();
	private final List<KeyExchangeListener> keyExchangeListeners = new ArrayList<>();
	private final List<EncryptedMessageDecryptionFailureListener> decryptionFailureListeners = new ArrayList<>();
	private final List<PersonalInfoChangeListener> infoChangeListeners = new ArrayList<>();
	/**
	 * PayloadCallback handles incoming data payloads.
	 * It processes received bytes as Protobuf messages and dispatches them.
	 */
	private final PayloadCallback payloadCallback = new PayloadCallback() {
		@Override
		public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
			executor.execute(() -> handleReceivedNearbyMessage(endpointId, payload));
		}

		@Override
		public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
			/*
			 * Handle payload transfer updates (e.g., progress, completion).
			 * For small messages like these, it might not be strictly necessary,
			 * but can be useful for larger file transfers.
			 */
			Log.d(TAG, "Payload transfer update: " + update.getPayloadId() + " " + update.getStatus());
		}
	};
	/**
	 * ConnectionLifecycleCallback handles the lifecycle of connections.
	 * It initiates connection, processes results, and handles disconnections.
	 */
	private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
		@Override
		public void onConnectionInitiated(@NonNull String endpointId, ConnectionInfo connectionInfo) {
			Log.d(TAG, "Connection initiated with: " + endpointId + " Name: " + connectionInfo.getEndpointName());
			pendingEndpointAddresses.put(endpointId, connectionInfo.getEndpointName());
			// Automatically accept all connections for simplicity in this example.
			// In a production app, you might want to show a dialog to the user for confirmation.
			connectionsClient.acceptConnection(endpointId, payloadCallback);
			deviceConnectionListeners.forEach(listener -> listener.onConnectionInitiated(connectionInfo.getEndpointName()));
		}

		@Override
		public void onConnectionResult(@NonNull String endpointId, ConnectionResolution result) {
			String remoteAddressName = pendingEndpointAddresses.remove(endpointId);
			if (result.getStatus().isSuccess()) {
				Log.d(TAG, "Connection established with: " + endpointId);
				// Store the mapping between endpointId and the remote device's SignalProtocolAddress name
				// The endpointName provided by Nearby Connections is the remote user's SignalProtocolAddress.name
				connectedEndpointAddresses.put(endpointId, remoteAddressName);
				addressToEndpointId.put(remoteAddressName, endpointId);

				deviceConnectionListeners.forEach(listener -> listener.onDeviceConnected(remoteAddressName));

				// Immediately initiate key exchange after connection is established
				executor.execute(() -> initiateKeyExchange(endpointId, remoteAddressName));
			} else {
				Log.e(TAG, "Connection failed with: " + endpointId + " Status: " + result.getStatus().getStatusMessage());
				deviceConnectionListeners.forEach(l -> l.onConnectionFailed(remoteAddressName, result.getStatus()));
			}
		}

		@Override
		public void onDisconnected(@NonNull String endpointId) {
			Log.d(TAG, "Disconnected from: " + endpointId);
			String deviceAddress = connectedEndpointAddresses.remove(endpointId);
			if (deviceAddress != null) {
				addressToEndpointId.remove(deviceAddress);
				deviceConnectionListeners.forEach(l -> l.onDeviceDisconnected(deviceAddress));
			}
		}
	};

	/**
	 * Constructor for NearbySignalMessenger.
	 *
	 * @param context           The application context.
	 * @param signalManager     The SignalManager instance for cryptographic operations.
	 * @param messengerCallback Used to notify caller of this method of operations which are to execute once, such as data persistence.
	 */
	public NearbySignalMessenger(@NonNull Context context, @NonNull SignalManager signalManager, @NonNull SignalMessengerCallback messengerCallback) {
		this.connectionsClient = Nearby.getConnectionsClient(context);
		this.signalManager = signalManager;
		this.messengerCallback = messengerCallback;
		this.executor = Executors.newSingleThreadExecutor(); // Single thread for ordered message processing
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
	 * Adds the listener for device connection/disconnection events.
	 *
	 * @param listener The implementation of DeviceConnectionListener.
	 */
	public void addDeviceConnectionListener(DeviceConnectionListener listener) {
		this.deviceConnectionListeners.add(listener);
	}

	/**
	 * Adds the listener for message sending to neighbor events.
	 *
	 * @param listener The implementation of {@link MessageSendingListener}.
	 */
	public void addDeviceConnectionListener(MessageSendingListener listener) {
		this.messageSendingListeners.add(listener);
	}

	/**
	 * Adds the listener for advertising events.
	 *
	 * @param listener The implementation of {@link AdvertisingListener}.
	 */
	public void addAdvertisingListener(AdvertisingListener listener) {
		this.advertisingListeners.add(listener);
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
	 * Adds the listener for failure event on decryption of encrypted message.
	 *
	 * @param listener The implementation of {@link EncryptedMessageDecryptionFailureListener}.
	 */
	public void addKeyExchangeListener(EncryptedMessageDecryptionFailureListener listener) {
		this.decryptionFailureListeners.add(listener);
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
	 * Returns {@code true} if currently advertising.
	 */
	protected boolean isAdvertising() {
		return isAdvertising;
	}

	/**
	 * Starts advertising this device to be discovered by others.
	 * The advertising name used is the device's SignalProtocolAddress name.
	 */
	public void startAdvertising() {
		if (isAdvertising()) {
			return;
		}
		AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder()
				.setStrategy(STRATEGY).build();

		connectionsClient.startAdvertising(
						signalManager.getLocalAddress().getName(), // Use local address name as advertising name
						SERVICE_ID,
						connectionLifecycleCallback,
						advertisingOptions
				).addOnSuccessListener(unused -> {
					Log.d(TAG, "Advertising started successfully");
					isAdvertising = true;
					advertisingListeners.forEach(AdvertisingListener::onAdvertisingStarted);
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to start advertising", e);
					advertisingListeners.forEach(l -> l.onAdvertisingFailed(e));
				});
	}

	/**
	 * Stops advertising.
	 */
	public void stopAdvertising() {
		connectionsClient.stopAdvertising();
		isAdvertising = false;
		Log.d(TAG, "Advertising stopped");
	}

	public boolean isDiscovering() {
		return isDiscovering;
	}

	/**
	 * Starts discovering other devices advertising the same service ID.
	 */
	public void startDiscovery() {
		if (isDiscovering()){
			Log.i(TAG, "Already discovering !");
			return;
		}
		DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder()
				.setStrategy(STRATEGY)
				.build();

		EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
			@Override
			public void onEndpointFound(@NonNull String endpointId, DiscoveredEndpointInfo info) {
				Log.d(TAG, "Endpoint found: " + info.getEndpointName() + " with ID: " + endpointId);
				// Request connection to the discovered endpoint.
				// In production, you might want to filter or prompt the user before connecting.
				connectionsClient.requestConnection(
								signalManager.getLocalAddress().getName(), // Local device's advertising name
								endpointId,
								connectionLifecycleCallback
						).addOnSuccessListener(unused -> Log.d(TAG, "Requested connection to " + info.getEndpointName()))
						.addOnFailureListener(e -> Log.e(TAG, "Failed to request connection to " + info.getEndpointName(), e));
			}

			@Override
			public void onEndpointLost(@NonNull String endpointId) {
				Log.d(TAG, "Endpoint lost: " + endpointId);
			}
		};

		connectionsClient.startDiscovery(
						SERVICE_ID,
						endpointDiscoveryCallback,
						discoveryOptions
				).addOnSuccessListener(unused -> Log.d(TAG, "Discovery started successfully"))
				.addOnFailureListener(e -> Log.e(TAG, "Failed to start discovery", e));
	}

	/**
	 * Stops discovering devices.
	 */
	public void stopDiscovery() {
		connectionsClient.stopDiscovery();
		Log.d(TAG, "Discovery stopped");
	}

	/**
	 * Initiates the Signal Protocol key exchange with a connected endpoint.
	 * This sends the local device's PreKeyBundle to the remote device.
	 *
	 * @param endpointId        The ID of the connected endpoint.
	 * @param remoteAddressName The SignalProtocolAddress name of the remote device.
	 */
	private void initiateKeyExchange(String endpointId, String remoteAddressName) {
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

			sendNearbyMessage(endpointId, nearbyMessage, null);

			Log.d(TAG, "Key exchange initiated with: " + remoteAddressName);
		} catch (Exception e) {
			Log.e(TAG, "Error initiating key exchange with " + remoteAddressName, e);
		}
	}

	/**
	 * Sends an encrypted message to a specific remote device, directly connected to this one.
	 * Before call this method, the caller must ensure the message is stored in database in
	 * pending state. This method will notice the messenger call back on success send of the message.
	 *
	 * @param remoteAddressName The SignalProtocolAddress name of the recipient device.
	 * @param message           The plaintext message to encrypt and send.
	 */
	public void sendEncryptedMessage(@NonNull String remoteAddressName, @NonNull TextMessage message) {
		String endpointId = addressToEndpointId.get(remoteAddressName);
		if (endpointId == null) {
			Log.e(TAG, "Cannot send message: no active connection to " + remoteAddressName);
			return;
		}

		executor.execute(() -> {
			try {
				/*
				 * Important : Cause the message must be wrapped in the payload, it
				 * is not possible to write the payload ID in the message. But after creating
				 * the payload, the local message will be persisted with the correct payload
				 * ID. Thus, method of handling the message receive MUST TAKE CARE TO WRITE/SAVE
				 * THE CORRECT PAYLOAD ID.
				 */
				SignalProtocolAddress recipientAddress = getAddress(remoteAddressName);
				NearbyMessageBody body = NearbyMessageBody.newBuilder()
						.setMessageType(ENCRYPTED_MESSAGE)
						.setEncryptedData(message.toByteString())
						.build();
				//The message is assumed stored in DB in state `Pending` before this method call
				byte[] plain = body.toByteArray();
				signalManager.encryptMessage(recipientAddress, plain, new EncryptionCallback() {
					@Override
					public void onSuccess(CiphertextMessage cipherMessage) {
						// Serialize the CiphertextMessage into a byte array
						byte[] cipherData = cipherMessage.serialize();

						// Wrap the encrypted message in a generic NearbyMessage
						NearbyMessage nearbyMessage = NearbyMessage.newBuilder()
								.setExchange(false)
								.setBody(ByteString.copyFrom(cipherData))
								.build();

						sendNearbyMessage(endpointId, nearbyMessage, (payload, success) -> {
							if (success) {
								// Send succeed
								messengerCallback.onSendSucceed(remoteAddressName, payload);
								messageSendingListeners.forEach(l -> l.onSendSucceed(remoteAddressName,
										message.toBuilder().setPayloadId(payload.getId()).build()));
							} else {
								// sending fails
								messengerCallback.onSendFailed(remoteAddressName, message);
							}
						});
					}

					@Override
					public void onError(Exception e) {
						Log.e(TAG, "Encryption failed for " + remoteAddressName + ": " + e.getMessage(), e);
					}
				});
			} catch (Exception e) {
				Log.e(TAG, "Error preparing message for " + remoteAddressName, e);
			}
		});
	}

	/**
	 * Sends a message read acknowledgement (ACK) to the recipient.
	 * This method will encrypt the ACK and send it via Nearby Connections.
	 *
	 * @param payloadId            The original message's payload ID that is being acknowledged as read.
	 * @param recipientAddressName The Signal Protocol address name of the message sender (who will receive this ACK).
	 */
	public void sendMessageReadAck(long payloadId, @NonNull String recipientAddressName) {
		final String endpointId = addressToEndpointId.get(recipientAddressName);
		if (endpointId == null) {
			return;
		}
		executor.execute(() -> {
			// Create the message ack
			MessageAck ack = MessageAck.newBuilder()
					.setPayloadId(payloadId)
					.build();
			// Wrap in a NearbyMessageBody
			NearbyMessageBody nearbyMessageBody = NearbyMessageBody.newBuilder()
					.setMessageType(NearbyMessageBody.MessageType.MESSAGE_READ_ACK)
					.setEncryptedData(ack.toByteString())
					.build();
			SignalProtocolAddress recipientAddress = getAddress(recipientAddressName);
			signalManager.encryptMessage(recipientAddress, nearbyMessageBody.toByteArray(), new EncryptionCallback() {
				@Override
				public void onSuccess(CiphertextMessage cipherMessage) {
					NearbyMessage message = NearbyMessage.newBuilder()
							.setExchange(false)
							.setBody(ByteString.copyFrom(cipherMessage.serialize())).build();
					sendNearbyMessage(endpointId, message, null);
				}

				@Override
				public void onError(Exception unused) {
					Log.e(TAG, "Encrypting message ack failed !");
					/*
					 * Until we integrated to save the time at which the message is read, we don't need to report
					 * this error. We will just expect next time, probably when user open the discussion next time,
					 * to re-send the message ack.
					 */
				}
			});
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
			String senderAddressName = connectedEndpointAddresses.get(endpointId);

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
					connectedEndpointAddresses.put(endpointId, senderAddressName);
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
								messengerCallback.persistTextMessage(textMessage, senderAddressName);
								messageReceivedListeners.forEach(l -> l.onMessageReceived(senderAddressName, textMessage));
								break;
							case MESSAGE_DELIVERED_ACK:
								MessageAck deliveredAck = MessageAck.parseFrom(decryptedMessageBody.getEncryptedData());
								messengerCallback.markMessageAsOfStatus(deliveredAck.getPayloadId(), true);
								break;
							case MESSAGE_READ_ACK:
								MessageAck readAck = MessageAck.parseFrom(decryptedMessageBody.getEncryptedData());
								messengerCallback.markMessageAsOfStatus(readAck.getPayloadId(), false);
								break;
							case PERSONAL_INFO:
							case CONTACT_UPDATE_INFO:
								PersonalInfo personalInfo = PersonalInfo.parseFrom(decryptedMessageBody.getEncryptedData());
								messengerCallback.mapsPersonalInfo(personalInfo, senderAddressName);
								// Notify mainly the UI
								infoChangeListeners.forEach(l -> l.onInfoChanged(personalInfo));
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
	}

	/**
	 * Helper method to send a Protobuf NearbyMessage over a connection.
	 *
	 * @param endpointId The ID of the endpoint to send the message to.
	 * @param message    The NearbyMessage Protobuf object to send.
	 * @param callback   consume the payload, after attempt to send the message, and a boolean
	 *                   specifying success or failure of the message.
	 */
	private void sendNearbyMessage(@NonNull String endpointId, @NonNull NearbyMessage message, BiConsumer<Payload, Boolean> callback) {
		Payload payload = Payload.fromBytes(message.toByteArray());
		connectionsClient.sendPayload(endpointId, payload)
				.addOnSuccessListener(unused -> {
					Log.d(TAG, "Payload sent to " + endpointId);
					if (callback != null) {
						callback.accept(payload, true);
					}
				})
				.addOnFailureListener(e -> {
					Log.e(TAG, "Failed to send payload to " + endpointId, e);
					if (callback != null) {
						callback.accept(payload, false);
					}
				});
	}

	/**
	 * Signal Messenger Callback
	 */
	public interface SignalMessengerCallback {
		/**
		 * Persist the message and send (or request for) delivered ack
		 */
		void persistTextMessage(TextMessage message, String senderAddressName);

		/**
		 * Marks the bound message to the payload ID as delivered or read.
		 *
		 * @param payloadId The payload ID of the message to mark
		 * @param delivered use {@code true} to mark the message as delivered,
		 *                  and {@code false} to mark the message as read.
		 */
		void markMessageAsOfStatus(long payloadId, boolean delivered);

		/**
		 * Maps personal info. This method has the responsibility to test
		 * {@link PersonalInfo#getExpectResult()} to know if a response is
		 * to send to sender, and in that case the implementer must initiate
		 * the response sending.
		 *
		 * @param info              wrapper of personal info of the sender
		 * @param senderAddressName the sender address name.
		 *                          This will be used to check if the packet is really sent by the real node.
		 */
		void mapsPersonalInfo(PersonalInfo info, String senderAddressName);

		/**
		 * Called when the message sending fails. You might need to mark the local message
		 * as in status failed.
		 *
		 * @param recipientAddressName the Signal address name of the recipient
		 * @param message              the plaintext message failed to send
		 */
		void onSendFailed(String recipientAddressName, TextMessage message);

		/**
		 * Called when the payload of the message was successfully sent to the neighbor.
		 * Here is the way to update the payload ID in the local message and to mark it
		 * as delivered.
		 *
		 * @param recipientAddressName the Signal address name of the recipient
		 * @param payload              the payload in which the message has been wrapped before sending through the network
		 */
		void onSendSucceed(String recipientAddressName, Payload payload);
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
		 * @param message              the message newly sent.
		 */
		void onSendSucceed(String recipientAddressName, TextMessage message);
	}

	// Callbacks for UI/application layer to react to events
	public interface MessageReceivedListener {
		void onMessageReceived(String senderAddress, TextMessage message);
	}

	public interface DeviceConnectionListener {
		void onConnectionInitiated(String deviceAddress);

		void onDeviceConnected(String deviceAddress);

		void onConnectionFailed(String deviceAddress, Status status);

		void onDeviceDisconnected(String deviceAddress);
	}

	/**
	 * Listening advertising
	 */
	public interface AdvertisingListener {
		void onAdvertisingStarted();

		void onAdvertisingFailed(Exception e);
	}

	/**
	 * Key exchange listener
	 */
	public interface KeyExchangeListener {
		//Might save the bundle key
		void onReadyToInitiateSession(PreKeyBundle preKeyBundle, String remoteAddressName, long payloadId);

		void onSessionInitSuccess(String remoteAddressName);

		void onSessionInitFailed(String remoteAddressName, Exception e);
	}

	/**
	 * Encrypted message decryption failure listener
	 */
	public interface EncryptedMessageDecryptionFailureListener {
		void onFailure(String senderEndpointId, String senderAddressName, long payloadId, Exception e);
	}

	/**
	 * Catch change on node's personal info
	 */
	public interface PersonalInfoChangeListener {
		void onInfoChanged(PersonalInfo info);
	}
}