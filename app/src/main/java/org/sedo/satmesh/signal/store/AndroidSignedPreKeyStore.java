package org.sedo.satmesh.signal.store;

import android.util.Log;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.signal.model.SignalSignedPreKeyDao;
import org.sedo.satmesh.signal.model.SignalSignedPreKeyEntity;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyStore;

import java.util.ArrayList;
import java.util.List;

/**
 * An Android-specific implementation of the {@link SignedPreKeyStore} interface.
 * This class is responsible for storing, retrieving, and managing signed pre-keys
 * using an underlying {@link AppDatabase} and {@link SignalSignedPreKeyDao}.
 * It follows a singleton pattern for managing its instance.
 *
 * @author hsedo777
 */
public class AndroidSignedPreKeyStore implements SignedPreKeyStore {

	/**
	 * Tag for logging.
	 */
	private static final String TAG = "SignedPreKeyStore";
	/**
	 * Singleton instance of the AndroidSignedPreKeyStore.
	 */
	private static volatile AndroidSignedPreKeyStore INSTANCE;
	/**
	 * Data Access Object for signed pre-key operations.
	 */
	private final SignalSignedPreKeyDao signedPreKeyDao;

	/**
	 * Constructs an AndroidSignedPreKeyStore with the given DAO.
	 * Private to enforce singleton pattern.
	 *
	 * @param signedPreKeyDao The Data Access Object for signed pre-key entities.
	 */
	private AndroidSignedPreKeyStore(SignalSignedPreKeyDao signedPreKeyDao) {
		this.signedPreKeyDao = signedPreKeyDao;
	}

	/**
	 * Returns the singleton instance of the AndroidSignedPreKeyStore.
	 * Initializes the instance if it hasn't been created yet, using the
	 * application's database.
	 *
	 * @return The singleton {@link AndroidSignedPreKeyStore} instance.
	 */
	public static AndroidSignedPreKeyStore getInstance() {
		if (INSTANCE == null) {
			synchronized (AndroidSignedPreKeyStore.class) {
				// At least the database must be initialized by the main activity
				AppDatabase db = AppDatabase.getDB(null);
				if (INSTANCE == null && db != null) {
					INSTANCE = new AndroidSignedPreKeyStore(db.signedPreKeyDao());
				}
			}
		}
		return INSTANCE;
	}

	/**
	 * {@inheritDoc}
	 * Loads a {@link SignedPreKeyRecord} from local storage.
	 *
	 * @param signedPreKeyId The ID of the signed pre-key to load.
	 * @return The loaded {@link SignedPreKeyRecord}.
	 * @throws InvalidKeyIdException if the key is not found or deserialization fails.
	 */
	@Override
	public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
		SignalSignedPreKeyEntity signedPreKeyEntity = signedPreKeyDao.getSignedPreKey(signedPreKeyId);
		if (signedPreKeyEntity != null) {
			try {
				return new SignedPreKeyRecord(signedPreKeyEntity.record());
			} catch (Exception e) {
				Log.e(TAG, "Fatal ! SignedPreKey Deserialization failed: " + signedPreKeyId, e);
				throw new InvalidKeyIdException("Fatal ! SignedPreKey Deserialization failed: " + signedPreKeyId);
			}
		} else {
			Log.e(TAG, "SignedPreKey not found: " + signedPreKeyId);
			throw new InvalidKeyIdException("SignedPreKey not found: " + signedPreKeyId);
		}
	}

	/**
	 * {@inheritDoc}
	 * Corrupted records are logged and removed.
	 *
	 * @return A list of all stored {@link SignedPreKeyRecord}s.
	 */
	@Override
	public List<SignedPreKeyRecord> loadSignedPreKeys() {
		List<SignalSignedPreKeyEntity> entities = signedPreKeyDao.getAllSignedPreKeys();
		List<SignedPreKeyRecord> records = new ArrayList<>();

		for (SignalSignedPreKeyEntity entity : entities) {
			try {
				records.add(new SignedPreKeyRecord(entity.record()));
			} catch (Exception e) {
				// Compromised enrollment
				Log.e(TAG, "Corrupted SignedPreKey found and ignored/removed: " + entity.keyId(), e);
				signedPreKeyDao.deleteSignedPreKey(entity.keyId()); // Optional
			}
		}
		return records;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @param signedPreKeyId The ID of the signed pre-key to store.
	 * @param record         The {@link SignedPreKeyRecord} to store.
	 */
	@Override
	public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
		SignalSignedPreKeyEntity signedPreKeyEntity = new SignalSignedPreKeyEntity(signedPreKeyId, record.serialize());
		signedPreKeyDao.insertSignedPreKey(signedPreKeyEntity);
	}

	/**
	 * {@inheritDoc}
	 * Checks if a {@link SignedPreKeyRecord} exists in local storage.
	 *
	 * @param signedPreKeyId The ID of the signed pre-key to check.
	 * @return true if the key exists, false otherwise.
	 */
	@Override
	public boolean containsSignedPreKey(int signedPreKeyId) {
		return signedPreKeyDao.getSignedPreKey(signedPreKeyId) != null;
	}

	/**
	 * {@inheritDoc}
	 * Removes a {@link SignedPreKeyRecord} from local storage.
	 *
	 * @param signedPreKeyId The ID of the signed pre-key to remove.
	 */
	@Override
	public void removeSignedPreKey(int signedPreKeyId) {
		signedPreKeyDao.deleteSignedPreKey(signedPreKeyId);
	}

	/**
	 * Retrieves the most recently stored (latest) signed pre-key.
	 * This is typically the active signed pre-key.
	 *
	 * @return The latest {@link SignedPreKeyRecord}.
	 * @throws InvalidKeyIdException if no active signed pre-key is found, or if the
	 *                               found key is corrupted (in which case it's also deleted).
	 */
	public SignedPreKeyRecord getLatestSignedPreKey() throws InvalidKeyIdException {
		SignalSignedPreKeyEntity entity = signedPreKeyDao.getLatestSignedPreKey(); // Ou getSignedPreKey(KNOWN_ACTIVE_ID);
		if (entity != null) {
			try {
				return new SignedPreKeyRecord(entity.record());
			} catch (Exception e) {
				// Corrupted active SignedPreKey
				Log.e(TAG, "Corrupted active SignedPreKey: " + entity.keyId() + ". Deleting it.", e);
				signedPreKeyDao.deleteSignedPreKey(entity.keyId());
				throw new InvalidKeyIdException(new RuntimeException("Active SignedPreKey corrupted and removed.", e));
			}
		}
		// No active SignedPreKey
		Log.e(TAG, "No active SignedPreKey found.");
		throw new InvalidKeyIdException("No active SignedPreKey found.");
	}
}
