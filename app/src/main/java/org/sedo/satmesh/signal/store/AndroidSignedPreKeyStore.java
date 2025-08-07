package org.sedo.satmesh.signal.store;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.signal.model.SignalSignedPreKeyDao;
import org.sedo.satmesh.signal.model.SignalSignedPreKeyEntity;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyStore;

import java.util.ArrayList;
import java.util.List;

public class AndroidSignedPreKeyStore implements SignedPreKeyStore {
	private final SignalSignedPreKeyDao signedPreKeyDao;

	private static AndroidSignedPreKeyStore INSTANCE;

	public static AndroidSignedPreKeyStore getInstance(){
		if (INSTANCE == null){
			synchronized (AndroidSignedPreKeyStore.class){
				// At least the database must be initialized by the main activity
				AppDatabase db = AppDatabase.getDB(null);
				if (INSTANCE == null && db != null){
					INSTANCE = new AndroidSignedPreKeyStore(db.signedPreKeyDao());
				}
			}
		}
		return INSTANCE;
	}

	protected AndroidSignedPreKeyStore(SignalSignedPreKeyDao signedPreKeyDao) {
		this.signedPreKeyDao = signedPreKeyDao;
	}

	@Override
	public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
		SignalSignedPreKeyEntity signedPreKeyEntity = signedPreKeyDao.getSignedPreKey(signedPreKeyId);
		if (signedPreKeyEntity != null) {
			try {
				return new SignedPreKeyRecord(signedPreKeyEntity.record());
			} catch (Exception e) {
				throw new InvalidKeyIdException("Fatal ! SignedPreKey Deserialization failed: " + signedPreKeyId);
			}
		} else {
			throw new InvalidKeyIdException("SignedPreKey not found: " + signedPreKeyId);
		}
	}

	@Override
	public List<SignedPreKeyRecord> loadSignedPreKeys() {
		List<SignalSignedPreKeyEntity> entities = signedPreKeyDao.getAllSignedPreKeys();
		List<SignedPreKeyRecord> records = new ArrayList<>();

		for (SignalSignedPreKeyEntity entity : entities) {
			try {
				records.add(new SignedPreKeyRecord(entity.record()));
			} catch (Exception e) {
				// Compromised enrollment
				System.err.println("Corrupted SignedPreKey found and ignored/removed: " + entity.keyId() + ". Error: " + e.getMessage());
				signedPreKeyDao.deleteSignedPreKey(entity.keyId()); // Optional
			}
		}
		return records;
	}

	@Override
	public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
		SignalSignedPreKeyEntity signedPreKeyEntity = new SignalSignedPreKeyEntity(signedPreKeyId, record.serialize());
		signedPreKeyDao.insertSignedPreKey(signedPreKeyEntity);
	}

	@Override
	public boolean containsSignedPreKey(int signedPreKeyId) {
		return signedPreKeyDao.getSignedPreKey(signedPreKeyId) != null;
	}

	@Override
	public void removeSignedPreKey(int signedPreKeyId) {
		signedPreKeyDao.deleteSignedPreKey(signedPreKeyId);
	}

	/** Get the latest generated signed prekey. */
	public SignedPreKeyRecord getLatestSignedPreKey() throws InvalidKeyIdException {
		SignalSignedPreKeyEntity entity = signedPreKeyDao.getLatestSignedPreKey(); // Ou getSignedPreKey(KNOWN_ACTIVE_ID);
		if (entity != null) {
			try {
				return new SignedPreKeyRecord(entity.record());
			} catch (Exception e) {
				System.err.println("Corrupted active SignedPreKey: " + entity.keyId() + ". Deleting it. Error: " + e.getMessage());
				signedPreKeyDao.deleteSignedPreKey(entity.keyId());
				throw new InvalidKeyIdException(new RuntimeException("Active SignedPreKey corrupted and removed.", e));
			}
		}
		throw new InvalidKeyIdException("No active SignedPreKey found.");
	}
}
