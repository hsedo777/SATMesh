package org.sedo.satmesh.signal.store;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.signal.model.SignalIdentityKeyDao;
import org.sedo.satmesh.signal.model.SignalIdentityKeyEntity;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.IdentityKeyStore;

import java.util.Arrays;

/**
 * An implementation of {@link IdentityKeyStore} for Android, persisting identity keys
 * using Room database. This class manages the local identity key pair and registration ID,
 * and handles saving and trusting remote identity keys.
 * It follows the Singleton pattern to ensure a single instance across the application.
 */
public class AndroidIdentityKeyStore implements IdentityKeyStore {
	private static volatile AndroidIdentityKeyStore INSTANCE; // Use volatile for thread-safe singleton
	private final SignalIdentityKeyDao identityKeyDao;
	private final IdentityKeyPair identityKeyPair;
	private final int registrationId;

	/**
	 * Constructs a new AndroidIdentityKeyStore.
	 * This constructor is protected to be used by the singleton factory method.
	 *
	 * @param identityKeyDao  The DAO for accessing identity key entities in the database.
	 * @param identityKeyPair The local identity key pair.
	 * @param registrationId  The local registration ID.
	 */
	protected AndroidIdentityKeyStore(SignalIdentityKeyDao identityKeyDao,
	                                  IdentityKeyPair identityKeyPair,
	                                  int registrationId) {
		this.identityKeyDao = identityKeyDao;
		this.identityKeyPair = identityKeyPair;
		this.registrationId = registrationId;
	}

	/**
	 * Returns the singleton instance of {@link AndroidIdentityKeyStore}.
	 * This method ensures that the database is initialized before creating the store.
	 *
	 * @param identityKeyPair The local identity key pair.
	 * @param registrationId  The local registration ID.
	 * @return The singleton instance of AndroidIdentityKeyStore.
	 * @throws IllegalStateException if the database has not been initialized.
	 */
	public static AndroidIdentityKeyStore getInstance(IdentityKeyPair identityKeyPair, int registrationId) {
		if (INSTANCE == null) {
			synchronized (AndroidIdentityKeyStore.class) {
				// The database must be initialized by the main activity or Application class
				AppDatabase db = AppDatabase.getDB(null);
				if (db == null) {
					throw new IllegalStateException("AppDatabase has not been initialized. Call AppDatabase.init() first.");
				}
				if (INSTANCE == null) {
					INSTANCE = new AndroidIdentityKeyStore(db.identityKeyDao(), identityKeyPair, registrationId);
				}
			}
		}
		return INSTANCE;
	}

	/**
	 * Returns the local identity key pair.
	 *
	 * @return The local identity key pair.
	 */
	@Override
	public IdentityKeyPair getIdentityKeyPair() {
		return identityKeyPair;
	}

	/**
	 * Returns the local registration ID.
	 *
	 * @return The local registration ID.
	 */
	@Override
	public int getLocalRegistrationId() {
		return registrationId;
	}

	private String getAddress(SignalProtocolAddress address) {
		return address.getName() + "." + address.getDeviceId();
	}

	/**
	 * Saves a remote identity key for a given SignalProtocolAddress.
	 * If an identity key for the address already exists and is different, it indicates
	 * a potential identity change, and the new key is not saved.
	 *
	 * @param address     The SignalProtocolAddress of the remote party.
	 * @param identityKey The IdentityKey of the remote party.
	 * @return true if the identity was saved successfully or if it already matched, false if the identity changed.
	 */
	@Override
	public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
		String addressString = getAddress(address);
		SignalIdentityKeyEntity existing = identityKeyDao.getIdentityKey(addressString);

		if (existing != null && !Arrays.equals(existing.identityKey(), identityKey.serialize())) {
			// The identity key has changed - this might indicate an attack
			return false;
		}

		SignalIdentityKeyEntity identityKeyEntity = new SignalIdentityKeyEntity(addressString, identityKey.serialize());
		identityKeyDao.insertIdentityKey(identityKeyEntity);
		return true;
	}

	/**
	 * Checks if a given identity key is trusted for a specific SignalProtocolAddress.
	 * An identity is trusted if no previous identity is stored for the address, or
	 * if the provided identity key matches the one already stored.
	 *
	 * @param address     The SignalProtocolAddress of the remote party.
	 * @param identityKey The IdentityKey to check.
	 * @param direction   The direction of the message (SENDING or RECEIVING). Not used in this implementation.
	 * @return true if the identity is trusted, false otherwise.
	 */
	@Override
	public boolean isTrustedIdentity(SignalProtocolAddress address,
	                                 IdentityKey identityKey,
	                                 Direction direction) {
		String addressString = getAddress(address);
		SignalIdentityKeyEntity existing = identityKeyDao.getIdentityKey(addressString);

		return existing == null || Arrays.equals(existing.identityKey(), identityKey.serialize());
	}

	/**
	 * Retrieves the identity key for a given SignalProtocolAddress from the store.
	 *
	 * @param address The SignalProtocolAddress of the remote party.
	 * @return The IdentityKey if found, or null if not found or if an error occurs during deserialization.
	 */
	@Override
	public IdentityKey getIdentity(SignalProtocolAddress address) {
		String addressString = getAddress(address);
		SignalIdentityKeyEntity identityKeyEntity = identityKeyDao.getIdentityKey(addressString);

		if (identityKeyEntity != null) {
			try {
				// The offset 0 in IdentityKey constructor usually means it's the start of the byte array.
				return new IdentityKey(identityKeyEntity.identityKey(), 0);
			} catch (Exception e) {
				return null;
			}
		}
		return null;
	}

	public void deleteIdentityForAddress(SignalProtocolAddress address) {
		identityKeyDao.deleteIdentityForAddress(getAddress(address));
	}
}