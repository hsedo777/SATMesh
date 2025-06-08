package org.sedo.satmesh.signal;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.sedo.satmesh.proto.SignalPreKeyBundle;
import org.sedo.satmesh.signal.store.AndroidIdentityKeyStore;
import org.sedo.satmesh.signal.store.AndroidPreKeyStore;
import org.sedo.satmesh.signal.store.AndroidSessionStore;
import org.sedo.satmesh.signal.store.AndroidSignedPreKeyStore;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SignalManager {
	private static final String IDENTITY_KEY_PAIR_PREF = "identity_key_pair";
	private static final String REGISTRATION_ID_PREF = "registration_id";
	private static final String NEXT_PREKEY_ID_PREF = "next_prekey_id";
	private static final String NEXT_SIGNED_PREKEY_ID_PREF = "next_signed_prekey_id";

	private static final int PREKEY_GENERATION_BATCH_SIZE = 100; // Number of PreKeys to generate together
	private static final int MIN_AVAILABLE_PREKEYS = 10; // Minimal number of prekeys to keep available
	private static final long SIGNED_PREKEY_LIFETIME_MILLIS = 90L * 24 * 60 * 60 * 1000; // 90 days in milliseconds
	
	private static SignalManager INSTANCE;

	private final SharedPreferences preferences;
	private final ExecutorService executor;

	private IdentityKeyPair identityKeyPair;
	private int registrationId;
	private AndroidSessionStore sessionStore;
	private AndroidPreKeyStore preKeyStore;
	private AndroidSignedPreKeyStore signedPreKeyStore;
	private AndroidIdentityKeyStore identityKeyStore;

	protected SignalManager(@NonNull Context context) {
		this.preferences = context.getApplicationContext().getSharedPreferences("signal_prefs", Context.MODE_PRIVATE);
		this.executor = Executors.newSingleThreadExecutor();
	}
	
	public static SignalManager getInstance(@NonNull Context context){
		if (INSTANCE == null){
			synchronized (SignalManager.class){
				if (INSTANCE == null){
					INSTANCE = new SignalManager(context);
				}
			}
		}
		return INSTANCE;
	}

	// Utilities methods
	public static SignalProtocolAddress getAddress(String deviceAddressName) {
		return new SignalProtocolAddress(deviceAddressName, 1);
	}

	private static String byteArrayToHexString(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private static byte[] hexStringToByteArray(String hex) {
		int len = hex.length();
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
					+ Character.digit(hex.charAt(i + 1), 16));
		}
		return data;
	}

	public void shutdown() {
		executor.shutdown();
	}

	public void initialize(SignalInitializationCallback callback) {
		executor.execute(() -> {
			try {
				// Initialize or get Identity
				initializeIdentity();

				// Initialize the stores
				sessionStore = AndroidSessionStore.getInstance();
				preKeyStore = AndroidPreKeyStore.getInstance();
				signedPreKeyStore = AndroidSignedPreKeyStore.getInstance();
				identityKeyStore = AndroidIdentityKeyStore.getInstance(identityKeyPair, registrationId);

				// Generate the PreKeys if needed
				generatePreKeysIfNeeded();

				if (callback != null) {
					callback.onSuccess();
				}
			} catch (Exception e) {
				if (callback != null) {
					callback.onError(e);
				}
			}
		});
	}

	private void initializeIdentity() throws Exception {
		String identityKeyPairData = preferences.getString(IDENTITY_KEY_PAIR_PREF, null);
		int regId = preferences.getInt(REGISTRATION_ID_PREF, -1);

		if (identityKeyPairData != null && regId != -1) {
			// Get existing identity key
			identityKeyPair = new IdentityKeyPair(hexStringToByteArray(identityKeyPairData));
			registrationId = regId;
		} else {
			// Create new identity key
			identityKeyPair = KeyHelper.generateIdentityKeyPair();
			registrationId = KeyHelper.generateRegistrationId(false);

			// Save
			preferences.edit()
					.putString(IDENTITY_KEY_PAIR_PREF, byteArrayToHexString(identityKeyPair.serialize()))
					.putInt(REGISTRATION_ID_PREF, registrationId)
					.apply();
		}
	}

	private void generatePreKeysIfNeeded() throws Exception {
		// Generate PreKeys if needed
		int unusedPreKeyCount = preKeyStore.getUnusedPreKeyCount();

		if (unusedPreKeyCount < MIN_AVAILABLE_PREKEYS) {
			int startId = preferences.getInt(NEXT_PREKEY_ID_PREF, 1);
			List<PreKeyRecord> preKeys = KeyHelper.generatePreKeys(startId, PREKEY_GENERATION_BATCH_SIZE);

			for (PreKeyRecord preKey : preKeys) {
				preKeyStore.storePreKey(preKey.getId(), preKey); // Stock as 'used = false'
			}
			preferences.edit()
					.putInt(NEXT_PREKEY_ID_PREF, startId + PREKEY_GENERATION_BATCH_SIZE)
					.apply();
			System.out.println("Generated " + PREKEY_GENERATION_BATCH_SIZE + " new PreKeys. Total unused: " + preKeyStore.getUnusedPreKeyCount());
		} else {
			System.out.println("Sufficient PreKeys available: " + unusedPreKeyCount);
		}

		// Checking of generating the SignedPreKey
		SignedPreKeyRecord latestSignedPreKey = null;
		try {
			latestSignedPreKey = signedPreKeyStore.getLatestSignedPreKey();
		} catch (InvalidKeyIdException e) {
			// There is no active SignedPreKey or it is compromised, we need to generate a new one.
			System.out.println("No active SignedPreKey found or it was corrupted. Generating a new one.");
		}

		boolean shouldGenerateNewSignedPreKey = false;
		if (latestSignedPreKey == null) {
			shouldGenerateNewSignedPreKey = true;
		} else {
			// Check the prekey expiration
			long currentTime = System.currentTimeMillis();
			if (currentTime - latestSignedPreKey.getTimestamp() > SIGNED_PREKEY_LIFETIME_MILLIS) {
				System.out.println("Active SignedPreKey has expired. Generating a new one.");
				shouldGenerateNewSignedPreKey = true;
				signedPreKeyStore.removeSignedPreKey(latestSignedPreKey.getId());
			}
		}

		if (shouldGenerateNewSignedPreKey) {
			int signedPreKeyId = preferences.getInt(NEXT_SIGNED_PREKEY_ID_PREF, 1);
			SignedPreKeyRecord signedPreKey = KeyHelper.generateSignedPreKey(identityKeyPair, signedPreKeyId);
			signedPreKeyStore.storeSignedPreKey(signedPreKey.getId(), signedPreKey);

			preferences.edit()
					.putInt(NEXT_SIGNED_PREKEY_ID_PREF, signedPreKeyId + 1)
					.apply();
			System.out.println("Generated new SignedPreKey with ID: " + signedPreKey.getId());
		} else {
			System.out.println("Active SignedPreKey (ID: " + latestSignedPreKey.getId() + ") is still valid.");
		}
	}

	private void markPreKeyAsUsed(PreKeyRecord preKeyRecord) {
		preKeyStore.markPreKeyAsUsed(preKeyRecord.getId()); // Mark the PreKey as used

		// Generate if needed new PreKeys in background. It's important to maintain a pool of PreKeys.
		// As possible, marking a PreKey as read should be followed by PreKeys pool maintenance as followed :
		executor.execute(() -> {
			try {
				generatePreKeysIfNeeded();
			} catch (Exception e) {
				System.err.println("Error generating PreKeys in background: " + e.getMessage());
			}
		});
	}

	/**
	 * Get the public key bundle for initial session establishment.
	 * This method consumes an unused PreKey.
	 *
	 * @return The PreKeyBundle.
	 * @throws Exception if PreKeys or SignedPreKey are not available.
	 */
	public PreKeyBundle getPreKeyBundle() throws Exception {
		if (preKeyStore == null || signedPreKeyStore == null || identityKeyPair == null) {
			throw new IllegalStateException("SignalManager not initialized. Call initialize() first.");
		}

		// 1. Get the next unused PreKey, next mark it as used
		PreKeyRecord preKeyRecord = preKeyStore.getNextUnusedPreKey(); // Throw InvalidKeyIdException if not exists
		markPreKeyAsUsed(preKeyRecord);

		// 2. Get the current active SignedPreKey
		SignedPreKeyRecord signedPreKeyRecord = signedPreKeyStore.getLatestSignedPreKey();

		return new PreKeyBundle(
				registrationId,
				1, // deviceId
				preKeyRecord.getId(),
				preKeyRecord.getKeyPair().getPublicKey(),
				signedPreKeyRecord.getId(),
				signedPreKeyRecord.getKeyPair().getPublicKey(),
				signedPreKeyRecord.getSignature(),
				identityKeyPair.getPublicKey()
		);
	}

	/**
	 * Initiates a session with another device.
	 *
	 * @param recipientAddress The address of the recipient.
	 * @param preKeyBundle     The PreKeyBundle obtained from the recipient.
	 * @param callback         Callback for success or error.
	 */
	public void initiateSession(SignalProtocolAddress recipientAddress, PreKeyBundle preKeyBundle,
	                            SessionCallback callback) {
		executor.execute(() -> {
			try {
				SessionBuilder sessionBuilder = new SessionBuilder(
						sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, recipientAddress
				);
				sessionBuilder.process(preKeyBundle);

				if (callback != null) {
					callback.onSuccess();
				}
			} catch (Exception e) {
				if (callback != null) {
					callback.onError(e);
				}
			}
		});
	}

	/**
	 * Encrypts a message for a recipient.
	 *
	 * @param recipientAddress The address of the recipient.
	 * @param message          The message, as byte array, to encrypt.
	 * @param callback         Callback for success with CiphertextMessage or error.
	 */
	public void encryptMessage(SignalProtocolAddress recipientAddress, byte[] message, EncryptionCallback callback) {
		executor.execute(() -> {
			try {
				SessionCipher sessionCipher = new SessionCipher(
						sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, recipientAddress
				);
				CiphertextMessage cipherMessage = sessionCipher.encrypt(message);

				if (callback != null) {
					callback.onSuccess(cipherMessage);
				}
			} catch (Exception e) {
				if (callback != null) {
					callback.onError(e);
				}
			}
		});
	}

	/**
	 * Decrypts a received message.
	 *
	 * @param senderAddress The address of the sender.
	 * @param cipherMessage The encrypted message.
	 * @param callback      Callback for success with decrypted message string or error.
	 */
	public void decryptMessage(SignalProtocolAddress senderAddress, CiphertextMessage cipherMessage,
	                           DecryptionCallback callback) {
		executor.execute(() -> {
			try {
				SessionCipher sessionCipher = new SessionCipher(
						sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, senderAddress
				);

				byte[] decryptedBytes;
				if (cipherMessage.getType() == CiphertextMessage.PREKEY_TYPE) {
					PreKeySignalMessage preKeyMessage = (PreKeySignalMessage) cipherMessage;
					decryptedBytes = sessionCipher.decrypt(preKeyMessage);
				} else if (cipherMessage.getType() == CiphertextMessage.WHISPER_TYPE) {
					SignalMessage signalMessage = (SignalMessage) cipherMessage;
					decryptedBytes = sessionCipher.decrypt(signalMessage);
				} else {
					throw new IllegalArgumentException("Unsupported message type");
				}

				if (callback != null) {
					callback.onSuccess(decryptedBytes);
				}
			} catch (Exception e) {
				if (callback != null) {
					callback.onError(e);
				}
			}
		});
	}

	/**
	 * Serializes a PreKeyBundle into a byte array using Protocol Buffers.
	 * This is the recommended production implementation.
	 *
	 * @param bundle The PreKeyBundle to serialize.
	 * @return A byte array representing the serialized bundle.
	 */
	public byte[] serializePreKeyBundle(PreKeyBundle bundle) {
		SignalPreKeyBundle protoBundle = SignalPreKeyBundle.newBuilder()
				.setRegistrationId(bundle.getRegistrationId())
				.setDeviceId(bundle.getDeviceId())
				.setPreKeyId(bundle.getPreKeyId())
				.setPreKeyPublicKey(ByteString.copyFrom(bundle.getPreKey().serialize()))
				.setSignedPreKeyId(bundle.getSignedPreKeyId())
				.setSignedPreKeyPublicKey(ByteString.copyFrom(bundle.getSignedPreKey().serialize()))
				.setSignedPreKeySignature(ByteString.copyFrom(bundle.getSignedPreKeySignature()))
				.setIdentityKeyPublicKey(ByteString.copyFrom(bundle.getIdentityKey().serialize()))
				.build();
		return protoBundle.toByteArray();
	}

	/**
	 * Deserializes a byte array into a PreKeyBundle using Protocol Buffers.
	 * This is the recommended production implementation.
	 *
	 * @param data The byte array to deserialize.
	 * @return The reconstructed PreKeyBundle.
	 * @throws InvalidProtocolBufferException if the data is not a valid protobuf message.
	 * @throws InvalidKeyException            if any contained key is invalid.
	 */
	public PreKeyBundle deserializePreKeyBundle(byte[] data) throws InvalidProtocolBufferException, InvalidKeyException {
		SignalPreKeyBundle protoBundle = SignalPreKeyBundle.parseFrom(data);

		// Note: Curve.decodePoint expects the raw public key bytes.
		// ECPublicKey.serialize() provides these.
		ECPublicKey preKey = Curve.decodePoint(protoBundle.getPreKeyPublicKey().toByteArray(), 0);
		ECPublicKey signedPreKey = Curve.decodePoint(protoBundle.getSignedPreKeyPublicKey().toByteArray(), 0);
		IdentityKey identityKey = new IdentityKey(protoBundle.getIdentityKeyPublicKey().toByteArray(), 0);

		return new PreKeyBundle(
				protoBundle.getRegistrationId(),
				protoBundle.getDeviceId(),
				protoBundle.getPreKeyId(),
				preKey,
				protoBundle.getSignedPreKeyId(),
				signedPreKey,
				protoBundle.getSignedPreKeySignature().toByteArray(),
				identityKey
		);
	}

	// Callback interfaces
	public interface SignalInitializationCallback {
		void onSuccess();

		void onError(Exception e);
	}

	public interface SessionCallback {
		void onSuccess();

		void onError(Exception e);
	}

	public interface EncryptionCallback {
		void onSuccess(CiphertextMessage cipherMessage);

		void onError(Exception e);
	}

	public interface DecryptionCallback {
		void onSuccess(byte[] decryptedBytes);

		void onError(Exception e);
	}
}
