package org.sedo.satmesh.nearby;

import static org.sedo.satmesh.proto.NearbyMessageBody.MessageType.CLAIM_READ_ACK;
import static org.sedo.satmesh.proto.NearbyMessageBody.MessageType.ENCRYPTED_MESSAGE;
import static org.sedo.satmesh.proto.NearbyMessageBody.MessageType.MESSAGE_DELIVERED_ACK_VALUE;
import static org.sedo.satmesh.proto.NearbyMessageBody.MessageType.MESSAGE_READ_ACK_VALUE;
import static org.sedo.satmesh.signal.SignalManager.getAddress;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
import org.sedo.satmesh.nearby.data.DeviceConnectionListener;
import org.sedo.satmesh.nearby.data.PayloadListener;
import org.sedo.satmesh.nearby.data.TransmissionCallback;
import org.sedo.satmesh.proto.MessageAck;
import org.sedo.satmesh.proto.MessageAckConfirmation;
import org.sedo.satmesh.proto.NearbyMessage;
import org.sedo.satmesh.proto.NearbyMessageBody;
import org.sedo.satmesh.proto.PersonalInfo;
import org.sedo.satmesh.proto.PreKeyBundleExchange;
import org.sedo.satmesh.proto.RouteDestroyMessage;
import org.sedo.satmesh.proto.RouteRequestMessage;
import org.sedo.satmesh.proto.RouteResponseMessage;
import org.sedo.satmesh.proto.RoutedMessage;
import org.sedo.satmesh.proto.TextMessage;
import org.sedo.satmesh.service.SATMeshCommunicationService;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.UiUtils;
import org.sedo.satmesh.ui.data.MessageRepository;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;
import org.sedo.satmesh.utils.Constants;
import org.sedo.satmesh.utils.DataLog;
import org.sedo.satmesh.utils.NotificationType;
import org.sedo.satmesh.utils.ObjectHolder;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
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
import java.util.function.Consumer;

/**
 * Manages Nearby Connections for discovering devices, establishing connections,
 * and exchanging Signal Protocol related messages (PreKeyBundles and encrypted messages).
 * This class handles the low-level communication aspects and integrates with {@link SignalManager}
 * for cryptographic operations and {@link AppDatabase} for persistence.
 */
public class NearbySignalMessenger implements DeviceConnectionListener, PayloadListener {

	/**
	 * Minimum delay, in millisecond, to be elapsed before accept to resend message
	 * when we don't have any result about its last transmission.
	 */
	public static final long MESSAGE_RESEND_DELAY_MS = 10_000L; // 10 seconds
	/**
	 * Minimum delay, in millisecond, to be elapsed before accept to resend routed message
	 */
	public static final long ROUTED_MESSAGE_RESEND_DELAY_MS = 30_000L; // 30 seconds
	/**
	 * Minimum delay, in millisecond, to be elapsed before accept to resend message that
	 * is pending for key exchange
	 */
	public static final long PENDING_KEY_EXCHANGE_MESSAGE_RESEND_DELAY_MS = 20_000L; // 20 seconds
	/**
	 * List of message statues that are considered "standby".
	 */
	private static final List<Integer> MESSAGE_STANDBY_STATUSES = Arrays.asList(
			Message.MESSAGE_STATUS_FAILED,
			Message.MESSAGE_STATUS_PENDING_KEY_EXCHANGE,
			Message.MESSAGE_STATUS_ROUTING,
			Message.MESSAGE_STATUS_SENT
	);
	private static final String TAG = "NearbySignalMessenger";
	private static volatile NearbySignalMessenger INSTANCE;

	private final @NonNull SignalManager signalManager;
	private final @NonNull NearbyManager nearbyManager;
	private final @NonNull NearbyRouteManager nearbyRouteManager;
	private final @NonNull Node hostNode; // Represents our own device
	private final MessageRepository messageRepository;
	private final NodeRepository nodeRepository;
	private final ExecutorService executor;
	private final @NonNull Context applicationContext;
	private Node currentRemote;

	/**
	 * Private constructor to enforce Singleton pattern.
	 * Takes all necessary dependencies.
	 */
	private NearbySignalMessenger(
			@NonNull Context context, @NonNull NearbyManager nearbyManager, @NonNull SignalManager signalManager, @NonNull Node hostNode) {
		this.nearbyManager = nearbyManager;
		this.signalManager = signalManager;
		this.hostNode = hostNode;
		messageRepository = new MessageRepository(context);
		nodeRepository = new NodeRepository(context);
		this.executor = Executors.newSingleThreadExecutor(); // Single thread for ordered message processing
		this.nearbyRouteManager = new NearbyRouteManager(nearbyManager, context, executor);
		Log.d(TAG, "NearbySignalMessenger instance created with dependencies.");

		applicationContext = context.getApplicationContext();

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
			@NonNull Context context, @NonNull NearbyManager nearbyManager, @NonNull SignalManager signalManager, @NonNull Node hostNode) {
		if (INSTANCE == null) {
			synchronized (NearbySignalMessenger.class) {
				if (INSTANCE == null) {
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

	/**
	 * Refresh the personal data of the host node.
	 */
	public void refreshHostNode() {
		executor.execute(() -> {
			Node hostNodeFromDb = nodeRepository.findNodeSync(hostNode.getAddressName());
			hostNode.setPersonalInfo(hostNodeFromDb.toPersonalInfo(false));
		});
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
					Long oldLastSeen = node.getLastSeen();
					node.setLastSeen(System.currentTimeMillis());
					nodeRepository.update(node);
					notifyNeighborDiscovery(node, false);
					Log.d(TAG, "Node " + deviceAddressName + " marked as connected in DB.");
					if (hasSession(deviceAddressName)) {
						attemptResendFailedMessagesTo(node, null);
						long lastProfileUpdate = UiUtils.getAppDefaultSharedPreferences(applicationContext)
								.getLong(Constants.PREF_KEY_LAST_PROFILE_UPDATE, 0L);
						if (oldLastSeen == null || (lastProfileUpdate > 0L && oldLastSeen < lastProfileUpdate)) {
							sendPersonalInfo(hostNode.toPersonalInfo(true), deviceAddressName);
						}
					} else {
						handleInitialKeyExchange(deviceAddressName);
					}
				} else {
					// Create a new node entity if it doesn't exist.
					// This covers cases where a device is connected but not yet explicitly "discovered"
					// or persisted from a previous run.
					Node newNode = new Node();
					newNode.setAddressName(deviceAddressName);
					newNode.setLastSeen(System.currentTimeMillis());
					nodeRepository.insert(newNode, onSuccess -> {
						if (onSuccess) {
							notifyNeighborDiscovery(newNode, true);
						}
					});
					Log.d(TAG, "New Node " + deviceAddressName + " inserted and marked as connected in DB.");
					handleInitialKeyExchange(deviceAddressName);
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating/inserting node status on device connected", e);
			}
		});
	}

	// This method is called when Nearby Connections fails to establish a connection.
	@Override
	public void onConnectionFailed(@NonNull String endpointId, String deviceAddressName, Status status) {
		Log.e(TAG, "Connection failed for " + deviceAddressName + " with status: " + status);
		DataLog.logNodeEvent(DataLog.NodeDiscoveryEvent.FAILED, deviceAddressName, endpointId, "status=" + status);
	}

	// This method is called when Nearby Connections detects a disconnection.
	@Override
	public void onDeviceDisconnected(@NonNull String endpointId, @NonNull String deviceAddressName) {
		Log.d(TAG, "Device disconnected: " + deviceAddressName + " (EndpointId: " + endpointId + ")");
		DataLog.logNodeEvent(DataLog.NodeDiscoveryEvent.DISCONNECT, deviceAddressName, endpointId, null);
	}

	/**
	 * Handles the event when a NEW Nearby endpoint is found.
	 * Decides whether to initiate a connection based on the current connection state.
	 *
	 * @param endpointId          The ID of the discovered endpoint.
	 * @param endpointAddressName The Signal Protocol address name of the discovered endpoint.
	 */
	public void onEndpointFound(@NonNull String endpointId, @NonNull String endpointAddressName) {
		Log.i(TAG, "Attempting to request connection to " + endpointAddressName + " (ID: " + endpointId + ")");
		/*
		 * The actual connection success/failure will be handled by
		 * the onConnectionResult callback in NearbyManager's ConnectionLifecycleCallback.
		 */
		DataLog.logNodeEvent(DataLog.NodeDiscoveryEvent.FOUND, endpointAddressName, endpointId, null);
		nearbyManager.requestConnection(endpointId, endpointAddressName);
		DataLog.logNodeEvent(DataLog.NodeDiscoveryEvent.INIT_BY_HOST, endpointAddressName, endpointId, null);
	}

	/**
	 * Handles the event when a Nearby endpoint is lost (goes out of range).
	 * This method primarily serves for logging and potentially notifying other components,
	 * as NearbyManager's own onDisconnected callback handles active connections.
	 *
	 * @param endpointId        The ID of the lost endpoint.
	 * @param deviceAddressName The Signal Protocol address name of the lost endpoint.
	 */
	public void onEndpointLost(@NonNull String endpointId, @NonNull String deviceAddressName) {
		/*
		 * This method is primarily for notification and logging that a discovered endpoint is no
		 * longer available. The NearbyManager's internal mechanisms.
		 */
		Log.d(TAG, "handleEndpointLost: Lost " + deviceAddressName + " (ID: " + endpointId + ")");
		DataLog.logNodeEvent(DataLog.NodeDiscoveryEvent.LOST, deviceAddressName, endpointId, null);
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
	 * Encrypts a {@link NearbyMessageBody} for a specific recipient and encapsulates it
	 * into a {@link NearbyMessage} ready for transmission.
	 * <p>
	 * This method takes the raw, unencrypted content of a {@link NearbyMessageBody},
	 * encrypts it using the Signal Protocol with the recipient's public key,
	 * and then embeds the resulting {@link CiphertextMessage} as a byte string within a new
	 * {@link NearbyMessage}. The {@code exchange} field of the {@link NearbyMessage} is
	 * set to {@code false}, indicating that it's a standard message and not a key exchange.
	 *
	 * @param plainMessage         The data to be encrypted.
	 *                             It is assumed to be unencrypted at this point.
	 * @param recipientAddressName The {@code SignalProtocolAddress.name} of the
	 *                             recipient to whom this encrypted message is intended.
	 * @return A {@link NearbyMessage} containing the encrypted {@link NearbyMessageBody}
	 * ready to be sent over Nearby Connections.
	 * @throws Exception If an error occurs during the encryption process, such as issues with
	 *                   retrieving recipient keys or Signal Protocol session management.
	 */
	protected CiphertextMessage encrypt(@NonNull byte[] plainMessage, @NonNull String recipientAddressName) throws Exception {
		// Encrypt message
		SignalProtocolAddress recipientAddress = getAddress(recipientAddressName);
		CiphertextMessage ciphertextMessage = signalManager.encryptMessage(recipientAddress, plainMessage);
		if (ciphertextMessage.getType() == CiphertextMessage.PREKEY_TYPE) {
			Log.d(TAG, "OCCURRENCE OF CIPHER MESSAGE OF TYPE 'PREKEY_TYPE'");
		}
		return ciphertextMessage;
	}

	/**
	 * Delegated method to {@code NearbyManager#sendRoutableNearbyMessageInternal(...)}
	 */
	protected void sendNearbyMessageInternal(
			@NonNull NearbyMessageBody plainNearbyMessage, @NonNull String recipientAddressName,
			@NonNull TransmissionCallback transmissionCallback,
			@Nullable TransmissionCallback routeTransmissionCallback,
			@Nullable Consumer<Boolean> routeDiscoveryCallback) {
		final Consumer<Boolean> finalDiscoveryCallback = onSuccess -> {
			executor.execute(() -> {
				Node target = nodeRepository.findNodeSync(recipientAddressName);
				if (target == null) {
					Log.w(TAG, "Unable to locate, in DB, the node to which route discovery is initiated.");
					return;
				}
				if (onSuccess) {
					notifyRouteDiscoveryInitTo(target);
				} else {
					notifyRouteDiscoveryResult(target, false);
				}
			});
			if (routeDiscoveryCallback != null) {
				routeDiscoveryCallback.accept(onSuccess);
			}
		};
		nearbyManager.sendRoutableNearbyMessageInternal(plainNearbyMessage, recipientAddressName, transmissionCallback,
				Objects.requireNonNullElse(routeTransmissionCallback, TransmissionCallback.NULL_CALLBACK), finalDiscoveryCallback);
	}

	/**
	 * Helper method to update message status in the database.
	 *
	 * @param messageDbId The ID of the message in the local database.
	 * @param status      The new status to set.
	 * @param payloadId   The payload ID from Nearby Connections (can be null if not applicable).
	 * @param callback    called with true if operation succeed in database, false else. If {@code null} then it is ignored
	 */
	private void updateMessageStatus(@Nullable Long messageDbId, int status, @Nullable Long payloadId, @Nullable Consumer<Boolean> callback) {
		if (messageDbId == null && payloadId == null) {
			Log.e(TAG, "Impossible to update message status without knowing ID nor payload ID");
			return;
		}
		executor.execute(() -> {
			try {
				Message message;
				if (messageDbId != null) {
					message = messageRepository.getMessageByIdSync(messageDbId);
				} else {
					message = messageRepository.getMessageByPayloadIdSync(payloadId);
				}
				if (message != null) {
					message.setStatus(status);
					if (MESSAGE_STANDBY_STATUSES.contains(status)) {
						message.setLastSendingAttempt(System.currentTimeMillis());
					} else {
						// The message is known from receiver. Clear last sending attempt
						message.setLastSendingAttempt(null);
					}
					message.setPayloadId(payloadId); // Apply the Nearby Payload ID, only if the value is not previously defined
					// The following message update is VERY IMPORTANT
					if (callback != null) {
						messageRepository.updateMessage(message, callback);
					} else {
						messageRepository.updateMessage(message);
					}
					Log.d(TAG, "Message ID " + messageDbId + ", payloadId=" + payloadId + " status updated to " + status + ".");
				} else {
					Log.w(TAG, "Message with ID=" + messageDbId + ", payloadId=" + payloadId + " is not found for status update.");
				}
			} catch (Exception e) {
				Log.e(TAG, "Error updating message status for ID " + messageDbId, e);
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
	 * This method is called from an executor thread.
	 *
	 * @param remoteAddressName The SignalProtocolAddress name of the remote device.
	 */
	public void handleInitialKeyExchange(@NonNull String remoteAddressName) {
		// This method is called from an executor thread, so direct calls to `signalManager` are fine.
		executor.execute(() -> {
			try {
				Log.d(TAG, "handleInitialKeyExchange from " + hostNode.getAddressName() + " to " + remoteAddressName);
				// Checks if there is an old sent of the PreKeyBundle to the same remote device
				if (hasSession(remoteAddressName)) {
					Log.d(TAG, "Signal session already exists for: " + remoteAddressName);
					return;
				}
				String endpointId = nearbyManager.getLinkedEndpointId(remoteAddressName);
				if (endpointId == null) {
					Log.w(TAG, "No endpointId found for " + remoteAddressName);
					return;
				}
				// Prepare packet and sent
				PreKeyBundle localPreKeyBundle = signalManager.generateOurPreKeyBundle();
				byte[] serializedPreKeyBundle = signalManager.serializePreKeyBundle(localPreKeyBundle);

				PreKeyBundleExchange preKeyBundleExchange = PreKeyBundleExchange.newBuilder().
						setPreKeyBundle(ByteString.copyFrom(serializedPreKeyBundle)).build();

				NearbyMessage nearbyMessage = NearbyMessage.newBuilder().setExchange(true) // Indicate it's a key exchange message
						.setKeyExchangeMessage(preKeyBundleExchange).build();

				nearbyManager.sendNearbyMessage(endpointId, nearbyMessage.toByteArray(), TransmissionCallback.NULL_CALLBACK);
			} catch (Exception e) {
				Log.e(TAG, "Error initiating key exchange with " + remoteAddressName, e);
			}
		});
	}

	/**
	 * Sends an encrypted message to a specific remote device.
	 * Before calling this method, the message should already be stored in the database
	 * in a "pending" or "sending" state.
	 *
	 * @param recipientAddressName The SignalProtocolAddress name of the recipient device.
	 * @param textMessage          The plaintext TextMessage to encrypt and send.
	 * @param messageDbId          The ID of the message in the local database (used for updates).
	 * @param transmissionCallback This method already execute setting message to matched transmission status.
	 *                             Anyway, this callback is added to track all specific behavior
	 *                             according to caller on transmission success/failure.
	 *                             It may be very useful when this method is called in a loop.
	 */
	public void sendEncryptedTextMessage(
			@NonNull String recipientAddressName, @NonNull TextMessage textMessage,
			long messageDbId, @NonNull TransmissionCallback transmissionCallback) {
		executor.execute(() -> {
			try {
				// Construct NearbyMessageBody with the actual TextMessage
				NearbyMessageBody messageBody = NearbyMessageBody.newBuilder()
						.setMessageType(ENCRYPTED_MESSAGE).setBinaryData(textMessage.toByteString()).build();

				// Send the wrapped message
				sendNearbyMessageInternal(messageBody, recipientAddressName,
						new TransmissionCallback() {
							@Override
							public void onSuccess(@NonNull Payload payload) {
								Log.d(TAG, "TextMessage sent successfully to " + recipientAddressName);
								// Update message in DB with sent status and payload ID. Set status pending to message ack
								updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_SENT, payload.getId(), null);
								transmissionCallback.onSuccess(payload);
							}

							@Override
							public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
								Log.w(TAG, "Failed to send TextMessage to " + recipientAddressName);
								if (cause instanceof NoSessionException || cause instanceof InvalidMessageException) {
									handleInitialKeyExchange(recipientAddressName);
								}
								updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, payload != null ? payload.getId() : textMessage.getPayloadId(), null);
								transmissionCallback.onFailure(payload, cause);
							}
						},
						new TransmissionCallback() {
							@Override
							public void onSuccess(@NonNull Payload payload) {
								Log.d(TAG, "TextMessage put successfully on a route to " + recipientAddressName);
								updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_ROUTING, payload.getId(), null);
							}

							@Override
							public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
								Log.w(TAG, "Failed to put TextMessage on a route to " + recipientAddressName);
								if (cause instanceof NoSessionException || cause instanceof InvalidMessageException) {
									handleInitialKeyExchange(recipientAddressName);
								}
								updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, payload != null ? payload.getId() : textMessage.getPayloadId(), null);
							}
						},
						initiated -> updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, textMessage.getPayloadId(), null));
			} catch (Exception e) {
				Log.e(TAG, "Error encrypting or sending TextMessage to " + recipientAddressName, e);
				updateMessageStatus(messageDbId, Message.MESSAGE_STATUS_FAILED, textMessage.getPayloadId(), null);
				transmissionCallback.onFailure(null, e);
			}
		});
	}

	/**
	 * Sends a message acknowledgment (delivered or read) to a recipient.
	 * If sending ack success, the message is put in the convenient state.
	 *
	 * @param originalPayloadId    The ID of the original message payload being acknowledged.
	 * @param recipientAddressName The Signal Protocol address name of the message recipient.
	 * @param delivered            True if it's a delivered ACK, false if it's a read ACK.
	 * @param callback             Callback to notify the caller of the success/failure of sending the ACK.
	 */
	public void sendMessageAck(long originalPayloadId, @NonNull String recipientAddressName, boolean delivered, @Nullable Consumer<Boolean> callback) {
		executor.execute(() -> {
			Consumer<Boolean> finalCallback = callback != null ? callback : aBoolean -> {
			};
			try {
				MessageAck messageAck = MessageAck.newBuilder().setPayloadId(originalPayloadId).build();

				NearbyMessageBody messageBody = NearbyMessageBody
						.newBuilder().setMessageType(
								delivered ? NearbyMessageBody.MessageType.MESSAGE_DELIVERED_ACK : NearbyMessageBody.MessageType.MESSAGE_READ_ACK
						)
						.setBinaryData(messageAck.toByteString()).build();

				sendNearbyMessageInternal(messageBody, recipientAddressName,
						new TransmissionCallback() {
							@Override
							public void onSuccess(@NonNull Payload payload) {
								Log.d(TAG, (delivered ? "Delivered" : "Read") + " ACK sent for payload " + originalPayloadId + " to " + recipientAddressName);
								updateMessageStatus(null, delivered ? Message.MESSAGE_STATUS_DELIVERED : Message.MESSAGE_STATUS_READ, originalPayloadId, finalCallback);
							}

							@Override
							public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
								Log.e(TAG, "Failed to send " + (delivered ? "Delivered" : "Read") + " ACK for payload " + originalPayloadId + " to " + recipientAddressName);
								finalCallback.accept(false);
							}
						},
						// Now, `sentMessageAckConfirmation` and its dependencies handle correctly the status update.
						TransmissionCallback.NULL_CALLBACK, null);

			} catch (Exception e) {
				Log.e(TAG, "Error sending ACK for payload " + originalPayloadId + " to " + recipientAddressName, e);
				finalCallback.accept(false); // Notify failure
			}
		});
	}

	private void sendMessageAckConfirmation(@NonNull String recipientAddressName, long payloadId, int ackType) {
		executor.execute(() -> {
			try {
				Log.d(TAG, "Sending ACK confirmation for payload " + payloadId + " to " + recipientAddressName
						+ " with type " + ackType + ".");

				MessageAckConfirmation ackConfirmation = MessageAckConfirmation.newBuilder()
						.setAckType(ackType)
						.setPayloadId(payloadId)
						.build();

				NearbyMessageBody messageBody = NearbyMessageBody
						.newBuilder().setMessageType(NearbyMessageBody.MessageType.ACK_CONFIRMATION)
						.setBinaryData(ackConfirmation.toByteString()).build();

				sendNearbyMessageInternal(
						messageBody, recipientAddressName, TransmissionCallback.NULL_CALLBACK,
						TransmissionCallback.NULL_CALLBACK, null);

			} catch (Exception e) {
				Log.e(TAG, "Error sending ACK confirmation for payload " + payloadId + " to " + recipientAddressName, e);
			}
		});
	}

	/**
	 * When you sent a discussion message to a remote node and had received the
	 * delivered ack, you can claimMessageReadAck the read ack. Thus, if the message is read and
	 * you don't have the ack, it'll be delivered to you.
	 */
	public void claimReadAck(@NonNull Message message) {
		executor.execute(() -> {
			try {
				long originalPayloadId = message.getPayloadId();
				Node recipient = nodeRepository.findNodeSync(message.getRecipientNodeId());
				if (recipient == null) {
					Log.d(TAG, "Unable to fetch from db the node to which to claimMessageReadAck the read ack.");
					return;
				}
				String recipientAddressName = recipient.getAddressName();
				MessageAck ack = MessageAck.newBuilder().setPayloadId(originalPayloadId).build();
				NearbyMessageBody body = NearbyMessageBody.newBuilder()
						.setMessageType(CLAIM_READ_ACK)
						.setBinaryData(ack.toByteString()).build();
				sendNearbyMessageInternal(body, recipientAddressName, TransmissionCallback.NULL_CALLBACK, null, null);
			} catch (Exception e) {
				Log.d(TAG, "Error when claiming read ack", e);
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
			try {
				NearbyMessageBody messageBody = NearbyMessageBody.newBuilder().setMessageType(NearbyMessageBody.MessageType.PERSONAL_INFO).setBinaryData(info.toByteString()).build();
				sendNearbyMessageInternal(messageBody, recipientAddressName, TransmissionCallback.NULL_CALLBACK, null, null);

			} catch (Exception e) {
				Log.e(TAG, "Error sending PersonalInfo to " + recipientAddressName, e);
			}
		});
	}

	/**
	 * Resends a message to a recipient.
	 *
	 * @param message              The message to resend
	 * @param recipientAddressName The recipient address name
	 * @param callback             The callback to notify the caller of the success/failure
	 *                             of sending the message
	 * @param aborted              If not null, it's called on transmission aborted caused by
	 *                             a failure of setting message up to date in the database.
	 */
	private void resendMessage(
			@NonNull Message message, @NonNull String recipientAddressName,
			@Nullable TransmissionCallback callback, @Nullable Consumer<Boolean> aborted) {
		// Create a copy to avoid mutating the original object in the UI layer directly.
		Message messageToResend = new Message(message);
		// Before attempting to resend, update its status to PENDING
		messageToResend.setStatus(Message.MESSAGE_STATUS_PENDING);
		messageToResend.setLastSendingAttempt(System.currentTimeMillis());
		messageRepository.updateMessage(messageToResend, onSuccess -> {
			if (onSuccess) {
				TextMessage text = TextMessage.newBuilder()
						.setContent(messageToResend.getContent())
						.setPayloadId(Objects.requireNonNullElse(messageToResend.getPayloadId(), 0L))
						.setTimestamp(messageToResend.getTimestamp())
						.build();
				sendEncryptedTextMessage(recipientAddressName, text, messageToResend.getId(),
						Objects.requireNonNullElse(callback, TransmissionCallback.NULL_CALLBACK));
			} else { // if updating fails, abort the whole process
				if (aborted != null) {
					aborted.accept(true);
				} else {
					Log.w(TAG, "Message update failed for " + message.getId() + " during resend, but no abort callback was provided.");
				}
			}
		});
	}

	/**
	 * Attempts to resend messages that previously failed to send to the
	 * remote node and tries to send delivery ack for messages the previous attempt failed.
	 * This method should be called when a secure session is established.
	 *
	 * @param remoteNode     the remote node
	 * @param onFirstSuccess If not null, it's called on success transmission of one message.
	 *                       Implementation guarantee the call is once at most.
	 */
	public void attemptResendFailedMessagesTo(@NonNull Node remoteNode, @Nullable Consumer<Void> onFirstSuccess) {
		executor.execute(() -> {
			List<Message> messages = messageRepository.getMessagesInStatusesForRecipientSync(
					remoteNode.getId(), MESSAGE_STANDBY_STATUSES
			);

			if (messages != null && !messages.isEmpty()) {
				final ObjectHolder<Boolean> toContinue = new ObjectHolder<>();
				toContinue.post(true);
				final ObjectHolder<Boolean> firstSucceed = new ObjectHolder<>();
				firstSucceed.post(false);
				TransmissionCallback callback = new TransmissionCallback() {
					@Override
					public void onSuccess(@NonNull Payload payload) {
						// Great
						if (!firstSucceed.getValue() && onFirstSuccess != null) {
							onFirstSuccess.accept(null);
						}
						firstSucceed.post(true);
					}

					@Override
					public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
						toContinue.post(false);
					}
				};
				Log.d(TAG, "Found " + messages.size() + " message(s) to resend to " + remoteNode.getAddressName());
				for (Message message : messages) {
					if (!toContinue.getValue()) {
						break;
					}
					Long lastAttempt = message.getLastSendingAttempt();
					int status = message.getStatus();
					if (lastAttempt != null) {
						// The host has been noticed of ending of the message last transmission, analyze status
						@SuppressLint("SwitchIntDef") long delay = switch (message.getStatus()) {
							case Message.MESSAGE_STATUS_ROUTING -> ROUTED_MESSAGE_RESEND_DELAY_MS;
							case Message.MESSAGE_STATUS_PENDING_KEY_EXCHANGE ->
									PENDING_KEY_EXCHANGE_MESSAGE_RESEND_DELAY_MS;
							default -> MESSAGE_RESEND_DELAY_MS;
						};
						if (System.currentTimeMillis() - lastAttempt < delay) {
							// Wait few time, expecting possible response
							Log.d(TAG, "Wait few secondes before resend message with ID " + message.getId() + ". Last attempt: " + lastAttempt);
							continue;
						}
						Log.d(TAG, "Attempt to resend message with ID " + message.getId() + " that has last attempt at " + lastAttempt + ". Status: " + status + ".");
					} else {
						// This should not happen
						Log.w(TAG, "Message with ID " + message.getId() + " has no last attempt while having status " + status + ".");
					}
					resendMessage(message, remoteNode.getAddressName(), callback, isAborted -> toContinue.post(!isAborted));
				}
			} else {
				Log.d(TAG, "No failed messages found to resend for " + remoteNode.getDisplayName());
			}

			// Retrieve message for which sending message delivery ack failed
			List<Message> missingAck = messageRepository.getMessagesInStatusesFromSenderSync(remoteNode.getId(), Collections.singletonList(Message.MESSAGE_STATUS_PENDING));
			if (missingAck != null && !missingAck.isEmpty()) {
				for (Message message : missingAck) {
					sendMessageAck(message.getPayloadId(), remoteNode.getAddressName(), true, null);
				}
			}
		});
	}

	/**
	 * Handles manual resend of a message.
	 *
	 * @param message    The message to resend
	 * @param remoteNode The remote node
	 * @param aborted    It's called on transmission aborted caused by a failure of setting message
	 *                   up to date in the database or not eligibility of the message for resend.
	 */
	public void handleMessageManualResend(@NonNull Message message, @NonNull Node remoteNode, @NonNull Consumer<Boolean> aborted) {
		if (!message.isSentTo(remoteNode) || message.hadReceivedAck()) {
			aborted.accept(true);
			return;
		}
		if (message.isOnTransmissionQueue()) {
			// The message is currently on transmission
			aborted.accept(true);
			return;
		}
		resendMessage(message, remoteNode.getAddressName(), null, aborted);
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
			String senderAddressName = nearbyManager.getAddressNameForEndpoint(endpointId); // Get Signal address name

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
				nodeRepository.insert(newNode, null);
				Log.d(TAG, "New node " + senderAddressName + " inserted in DB upon message receipt.");
			}


			if (nearbyMessage.getExchange()) {
				// It's a key exchange message
				if (!nearbyMessage.hasKeyExchangeMessage()) {
					Log.e(TAG, "Received NearbyMessage with exchange=true but no key_exchange_message content from " + senderAddressName);
					return;
				}
				handleReceivedKeyExchange(senderAddressName, nearbyMessage.getKeyExchangeMessage());
			} else {
				// It's an encrypted message body
				if (nearbyMessage.getPayloadContentCase() != NearbyMessage.PayloadContentCase.BODY) {
					Log.e(TAG, "Received NearbyMessage with exchange=false but no body content from " + senderAddressName);
					return;
				}
				handleReceivedEncryptedMessage(senderAddressName, nearbyMessage.getBody().toByteArray(), payload.getId());
			}
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse NearbyMessage from " + endpointId, e);
		} catch (Exception e) {
			Log.e(TAG, "Unexpected error handling received NearbyMessage from " + endpointId, e);
		}
	}

	/**
	 * Handles a received PreKeyBundle from a remote device, establishing or updating a Signal session.
	 * This runs on the caller thread
	 *
	 * @param senderAddressName The SignalProtocolAddress name of the sender.
	 * @param bundleExchange    The {@code PreKeyBundleExchange} that wrap the prekey bundle.
	 */
	private void handleReceivedKeyExchange(@NonNull String senderAddressName, @NonNull PreKeyBundleExchange bundleExchange) {
		try {
			Log.d(TAG, "Received Key Exchange from: " + senderAddressName);
			SignalProtocolAddress remoteAddress = getAddress(senderAddressName);

			// Process the received PreKeyBundle using SignalManager
			// The method establishSessionFromRemotePreKeyBundle handles session establishment/updates
			signalManager.establishSessionFromRemotePreKeyBundle(remoteAddress,
					signalManager.deserializePreKeyBundle(bundleExchange.getPreKeyBundle().toByteArray()));

			// Send knowledge message
			byte[] data = new byte[]{0, 1};
			NearbyMessageBody body = NearbyMessageBody.newBuilder()
					.setMessageType(NearbyMessageBody.MessageType.KNOWLEDGE)
					.setBinaryData(ByteString.copyFrom(data)).build();

			nearbyManager.encryptAndSendInternal(null, senderAddressName, body,
					new TransmissionCallback() {
						@Override
						public void onSuccess(@NonNull Payload payload) {
							Log.d(TAG, "Knowledge message successfully sent to remote user at address=" + senderAddressName);
						}

						@Override
						public void onFailure(@Nullable Payload payload, @Nullable Exception cause) {
							Log.e(TAG, "Failed to send knowledge message to user at address=" + senderAddressName
									+ "\tsessionState=" + signalManager.hasSession(remoteAddress), cause);
							// It may be interesting to invalidate the session
						}
					});

			Log.d(TAG, "Signal session established/updated with " + senderAddressName + ". Endpoint mapped.");
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to deserialize PreKeyBundle from " + senderAddressName, e);
		} catch (InvalidKeyException e) {
			Log.e(TAG, "Invalid key in PreKeyBundle from " + senderAddressName, e);
		} catch (Exception e) {
			Log.e(TAG, "Error processing key exchange from " + senderAddressName, e);
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
			Log.e(TAG, "Error updating Node DB status for " + senderAddressName, e);
		}
	}

	@Nullable
	protected byte[] decrypt(byte[] cipherData, @NonNull String remoteAddressName) throws Exception {
		try {
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

			SignalProtocolAddress senderAddress = getAddress(remoteAddressName);
			return signalManager.decryptMessage(senderAddress, receivedCipherMessage);
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse decrypted message body from " + remoteAddressName, e);
		} catch (NoSessionException | InvalidMessageException e) {
			Log.e(TAG, "No Signal session to decrypt message from " + remoteAddressName, e);
			/*
			 * This could happen if sender sends message before session is fully established, or if our session was lost.
			 * We should re-initiate key exchange if possible, or request sender to re-send.
			 * Optionally, try to initiate key exchange again to fix session
			 */
			//executor.execute(() -> handleInitialKeyExchange(remoteAddressName));
		}
		return null;
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

			byte[] decryptedBytes = decrypt(cipherData, senderAddressName);
			if (decryptedBytes == null) {
				return;
			}
			NearbyMessageBody messageBody = NearbyMessageBody.parseFrom(decryptedBytes);

			// Now, we have the decrypted content (NearbyMessageBody).
			// We need to persist the received message if it's a TextMessage.
			// For ACKs or Info, we update existing entries or just notify.

			parseDecryptedMessage(messageBody, senderAddressName, payloadId, false);
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse decrypted message body from " + senderAddressName, e);
		} catch (Exception e) {
			Log.e(TAG, "Error processing encrypted message from " + senderAddressName, e);
		}
	}

	// Helper method

	protected void parseDecryptedMessage(
			@NonNull NearbyMessageBody messageBody, @NonNull String senderAddressName,
			long payloadId, boolean isRouted) throws Exception {
		SignalProtocolAddress senderAddress = getAddress(senderAddressName);
		Message message;
		switch (messageBody.getMessageType()) {
			case ENCRYPTED_MESSAGE:
				TextMessage textMessage = TextMessage.parseFrom(messageBody.getBinaryData());
				Node senderNode = nodeRepository.findNodeSync(senderAddressName);
				if (senderNode == null) {
					Log.e(TAG, "Failed to identify, in DB, the sender node at address " + senderAddress + " msg.payloadId=" + payloadId);
					return;
				}

				Log.d(TAG, "Received TextMessage from " + senderAddressName);
				if (textMessage.getPayloadId() != 0L) {
					// We are in case of retransmission
					long tmpPayloadId = textMessage.getPayloadId();
					Message retransmitted = messageRepository.getMessageByPayloadIdSync(tmpPayloadId);
					if (retransmitted != null) {
						Log.d(TAG, "Message multiple retransmission");
						int oldStatus = retransmitted.getStatus();
						/*
						 * The sender is trying to send the message again because it's neither
						 * marked delivered nor read by his side.
						 */
						sendMessageAck(payloadId, senderAddressName,
								// Ensure the message status is maintained locally
								oldStatus == Message.MESSAGE_STATUS_DELIVERED, null);
						return;
					}
					// Else, continue execution normally
					if (payloadId != tmpPayloadId) {
						Log.w(TAG, "Alert: receiving encrypted message with payloadId=" + tmpPayloadId + " where attempting to link payloadId=" + payloadId + ".");
						payloadId = tmpPayloadId;
					}
				}
				Log.d(TAG, "Persisting the message.");
				// Persist the received text message
				message = new Message();
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
						notifyMessageReceived(message, nodeRepository.findNodeSync(senderAddressName));
						sendMessageAck(finalPayloadId, senderAddressName, true, null);
					}
				});
				break;
			case MESSAGE_DELIVERED_ACK:
				MessageAck deliveredAck = MessageAck.parseFrom(messageBody.getBinaryData());
				// Update status of the original message in DB
				updateMessageStatus(null, Message.MESSAGE_STATUS_DELIVERED, deliveredAck.getPayloadId(), null);
				Log.d(TAG, "Received Delivered ACK for payload " + deliveredAck.getPayloadId() + " from " + senderAddressName);
				if (isRouted) {
					sendMessageAckConfirmation(senderAddressName, deliveredAck.getPayloadId(), MESSAGE_DELIVERED_ACK_VALUE);
				}
				break;
			case MESSAGE_READ_ACK:
				MessageAck readAck = MessageAck.parseFrom(messageBody.getBinaryData());
				// Update status of the original message in DB
				updateMessageStatus(null, Message.MESSAGE_STATUS_READ, readAck.getPayloadId(), null);
				Log.d(TAG, "Received Read ACK for payload " + readAck.getPayloadId() + " from " + senderAddressName);
				if (isRouted) {
					sendMessageAckConfirmation(senderAddressName, readAck.getPayloadId(), MESSAGE_READ_ACK_VALUE);
				}
				break;
			case ACK_CONFIRMATION:
				MessageAckConfirmation ackConfirmation = MessageAckConfirmation.parseFrom(messageBody.getBinaryData());
				Log.d(TAG, "Received ACK confirmation for payload " + ackConfirmation.getPayloadId() + " with type " + ackConfirmation.getAckType());
				if (ackConfirmation.getAckType() == MESSAGE_DELIVERED_ACK_VALUE) {
					updateMessageStatus(null, Message.MESSAGE_STATUS_DELIVERED, ackConfirmation.getPayloadId(), null);
				} else if (ackConfirmation.getAckType() == MESSAGE_READ_ACK_VALUE) {
					updateMessageStatus(null, Message.MESSAGE_STATUS_READ, ackConfirmation.getPayloadId(), null);
				} else {
					Log.w(TAG, "Received ACK confirmation with unhandled ackType: " + ackConfirmation.getAckType());
				}
				break;
			case PERSONAL_INFO:
				PersonalInfo personalInfo = PersonalInfo.parseFrom(messageBody.getBinaryData());
				// Update node's display name and potentially other info in DB
				Node nodeToUpdate = nodeRepository.findNodeSync(senderAddressName);
				if (nodeToUpdate != null) {
					nodeToUpdate.setPersonalInfo(personalInfo);
					nodeToUpdate.setLastSeen(System.currentTimeMillis()); // Update last seen as well
					nodeRepository.update(nodeToUpdate);
					Log.d(TAG, "Received and updated PersonalInfo for " + senderAddressName + ": " + personalInfo.getDisplayName());
					if (personalInfo.getExpectResult()) {
						sendPersonalInfo(hostNode.toPersonalInfo(false), senderAddressName);
					}
				} else {
					// This case might not happen cause exchanging personal info require secured session and the session requires node persistence.
					Log.d(TAG, "Received PersonalInfo for new node " + senderAddressName + ": " + personalInfo.getDisplayName());
					return;
				}
				break;
			case TYPING_INDICATOR:
				// TypingIndicator typingIndicator = TypingIndicator.parseFrom(messageBody.getBinaryData());
				// Log.d(TAG, "Received TypingIndicator from " + senderAddressName + ": " + typingIndicator.getIsTyping());
				// TODO: Notify UI via NodeStateRepository to show/hide typing indicator if needed
				break;
			case ROUTE_DISCOVERY_REQ:
				Log.d(TAG, "Received ROUTE_DISCOVERY_REQ from " + senderAddressName);
				RouteRequestMessage routeRequestMessage = RouteRequestMessage.parseFrom(messageBody.getBinaryData());
				nearbyRouteManager.handleIncomingRouteRequest(senderAddressName, routeRequestMessage, hostNode.getAddressName());
				break;
			case ROUTE_DISCOVERY_RESP:
				Log.d(TAG, "Received ROUTE_DISCOVERY_RESP from " + senderAddressName);
				RouteResponseMessage routeResponseMessage = RouteResponseMessage.parseFrom(messageBody.getBinaryData());
				nearbyRouteManager.handleIncomingRouteResponse(senderAddressName, routeResponseMessage);
				break;
			case ROUTED_MESSAGE:
				Log.d(TAG, "Parsing routed message relayed by neighbor: " + senderAddressName);
				RoutedMessage routedMessage = RoutedMessage.parseFrom(messageBody.getBinaryData());
				nearbyRouteManager.handleIncomingRoutedMessage(routedMessage, hostNode.getAddressName(), payloadId);
				break;
			case KNOWLEDGE:
				// Success decryption of the body, above, is enough
				Log.d(TAG, "Receiving KNOWLEDGE message from: " + senderAddress);
				Node hostCopy = nodeRepository.findNodeSync(hostNode.getAddressName());
				sendPersonalInfo(hostCopy.toPersonalInfo(true), senderAddressName);
				hostNode.setDisplayName(hostCopy.getDisplayName());
				break;
			case CLAIM_READ_ACK:
				MessageAck ack = MessageAck.parseFrom(messageBody.getBinaryData());
				Log.d(TAG, "Receiving claimMessageReadAck of read ack for message with payload ID: " + ack.getPayloadId());
				if (messageRepository.isMessageRead(ack.getPayloadId())) {
					Log.d(TAG, "Message is really in state read, let's send the read ack.");
					sendMessageAck(ack.getPayloadId(), senderAddressName, false, null);
				}
				break;
			case ROUTE_DESTROY:
				Log.d(TAG, "Received ROUTE_DESTROY from " + senderAddressName);
				RouteDestroyMessage destroy = RouteDestroyMessage.parseFrom(messageBody.getBinaryData());
				nearbyRouteManager.handleIncomingRouteDestroyMessage(destroy, senderAddressName);
				break;
			case UNRECOGNIZED:
			case UNKNOWN:
			default:
				Log.w(TAG, "Received UNKNOWN or unhandled message type: " + messageBody.getMessageType() + " from " + senderAddressName);
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
			notifyRouteDiscoveryResult(remoteNode, true);
			attemptResendFailedMessagesTo(remoteNode, null);
		});
	}

	public void onRouteNotFound(@NonNull String destinationAddressName) {
		executor.execute(() -> {
			Log.d(TAG, "Handling route not found to destination: " + destinationAddressName);
			Node remoteNode = nodeRepository.findNodeSync(destinationAddressName);
			if (remoteNode == null) {
				Log.e(TAG, "Unable to locate the remote node at address: " + destinationAddressName);
				return;
			}
			notifyRouteDiscoveryResult(remoteNode, false);
		});
	}

	/**
	 * Gets a reference of the {@link NearbyRouteManager} bound to this {@code NearbySignalMessenger}
	 */
	@NonNull
	public NearbyRouteManager getNearbyRouteManager() {
		return nearbyRouteManager;
	}

	/**
	 * Sets the remote node the user is currently interact with.
	 */
	public void setCurrentRemote(@Nullable Node currentRemote) {
		this.currentRemote = currentRemote;
		if (currentRemote != null) {
			dismissNotificationFor(currentRemote);
		}
	}

	/**
	 * Dismiss discussion message's notification actually displayed about a specific remote node.
	 *
	 * @param remoteNode the node about which to dismiss all message notifications.
	 */
	private void dismissNotificationFor(@NonNull Node remoteNode) {
		// Dismiss discussion message notifications
		Intent dismissMessages = new Intent(applicationContext, SATMeshCommunicationService.class);
		dismissMessages.putExtra(Constants.NOTIFICATION_GROUP_KEY, remoteNode.getAddressName());
		dismissMessages.setAction(Constants.ACTION_NOTIFICATION_DISMISSED);
		applicationContext.startService(dismissMessages);
	}

	/**
	 * Dispatches a notification request to the {@link SATMeshCommunicationService}.
	 * This method centralizes the logic for creating and sending the {@link Intent}
	 * to initiate a notification display by the service.
	 *
	 * @param data A {@link Bundle} containing the specific data required for the notification.
	 *             The content of this bundle depends on the {@code type} of notification.
	 * @param type The {@link NotificationType} enum indicating which type of notification
	 *             is to be displayed (e.g., new message, node discovery, route event).
	 */
	private void sendNotification(@NonNull Bundle data, @NonNull NotificationType type) {
		Intent notificationIntent = new Intent(applicationContext, SATMeshCommunicationService.class);
		notificationIntent.setAction(Constants.ACTION_SHOW_SATMESH_NOTIFICATION);
		notificationIntent.putExtra(Constants.EXTRA_NOTIFICATION_TYPE, type.name());
		notificationIntent.putExtra(Constants.EXTRA_NOTIFICATION_DATA_BUNDLE, data);

		applicationContext.startService(notificationIntent);
	}

	/**
	 * Prepares and sends a notification request for a new message received.
	 * This method constructs a data bundle with message-specific information
	 * and delegates the sending to {@link #sendNotification(Bundle, NotificationType)}.
	 *
	 * @param message The {@link Message} object that was received.
	 * @param sender  The {@link Node} object representing the sender of the message.
	 */
	private void notifyMessageReceived(@NonNull Message message, @NonNull Node sender) {
		if (currentRemote != null && Objects.equals(currentRemote.getAddressName(), sender.getAddressName())) {
			// User is currently in interaction with de the remote, node. Don't sent notification
			return;
		}
		Bundle data = new Bundle();
		data.putString(Constants.MESSAGE_SENDER_NAME, sender.getDisplayName());
		data.putString(Constants.MESSAGE_SENDER_ADDRESS, sender.getAddressName());
		data.putString(Constants.MESSAGE_CONTENT, message.getContent());
		data.putLong(Constants.MESSAGE_PAYLOAD_ID, message.getPayloadId());
		data.putLong(Constants.MESSAGE_ID, message.getId());

		sendNotification(data, NotificationType.NEW_MESSAGE);
	}

	/**
	 * Prepares and sends a notification request for a new neighbor node discovery.
	 * This method bundles relevant neighbor information and delegates
	 * the sending to {@link #sendNotification(Bundle, NotificationType)}.
	 *
	 * @param neighbor The {@link Node} object that was discovered.
	 * @param isNew    A boolean indicating if the discovered neighbor is entirely new (first time seen).
	 */
	private void notifyNeighborDiscovery(@NonNull Node neighbor, boolean isNew) {
		Bundle data = new Bundle();
		data.putString(Constants.NODE_ADDRESS, neighbor.getAddressName());
		data.putString(Constants.NODE_DISPLAY_NAME, neighbor.getDisplayName());
		data.putBoolean(Constants.NODE_IS_NEW, isNew);

		sendNotification(data, NotificationType.NEW_NODE_DISCOVERED);
	}

	/**
	 * Prepares and sends a notification request indicating the initiation of a route discovery process.
	 * This method bundles the target node's information and delegates
	 * the sending to {@link #sendNotification(Bundle, NotificationType)}.
	 *
	 * @param target The {@link Node} object for which a route discovery has been initiated.
	 */
	private void notifyRouteDiscoveryInitTo(@NonNull Node target) {
		Bundle data = new Bundle();
		data.putString(Constants.NODE_ADDRESS, target.getAddressName());
		data.putString(Constants.NODE_DISPLAY_NAME, target.getDisplayName());

		sendNotification(data, NotificationType.ROUTE_DISCOVERY_INITIATED);
	}

	/**
	 * Prepares and sends a notification request indicating the result of a route discovery.
	 * This method bundles the target node's information and the discovery outcome,
	 * then delegates the sending to {@link #sendNotification(Bundle, NotificationType)}.
	 *
	 * @param target The {@link Node} object for which the route discovery was performed.
	 * @param found  A boolean indicating whether a route to the target node was successfully found.
	 */
	private void notifyRouteDiscoveryResult(@NonNull Node target, boolean found) {
		Bundle data = new Bundle();
		data.putString(Constants.NODE_ADDRESS, target.getAddressName());
		data.putString(Constants.NODE_DISPLAY_NAME, target.getDisplayName());
		data.putBoolean(Constants.ROUTE_IS_FOUND, found);

		sendNotification(data, NotificationType.ROUTE_DISCOVERY_RESULT);
	}
}
