package org.sedo.satmesh.signal.store;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.signal.model.SignalPreKeyDao;
import org.sedo.satmesh.signal.model.SignalPreKeyEntity;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.PreKeyStore;

public class AndroidPreKeyStore implements PreKeyStore {
	private final SignalPreKeyDao preKeyDao;

	private static AndroidPreKeyStore INSTANCE;

	public static AndroidPreKeyStore getInstance(){
		if (INSTANCE == null){
			synchronized (AndroidPreKeyStore.class){
				// At least the database must be initialized by the main activity
				AppDatabase db = AppDatabase.getDB(null);
				if (INSTANCE == null && db != null){
					INSTANCE = new AndroidPreKeyStore(db.preKeyDao());
				}
			}
		}
		return INSTANCE;
	}

	protected AndroidPreKeyStore(SignalPreKeyDao preKeyDao) {
		this.preKeyDao = preKeyDao;
	}

	@Override
	public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
		SignalPreKeyEntity entity = preKeyDao.getPreKey(preKeyId);
		if (entity != null) {
			try {
				return new PreKeyRecord(entity.record);
			} catch (Exception e) {
				throw new InvalidKeyIdException("Fatal : unable to deserialize the PreKey: " + preKeyId);
			}
		} else {
			throw new InvalidKeyIdException("PreKey not found: " + preKeyId);
		}
	}

	@Override
	public void storePreKey(int preKeyId, PreKeyRecord record) {
		SignalPreKeyEntity preKeyEntity = new SignalPreKeyEntity(preKeyId, record.serialize(), false);
		preKeyDao.insertPreKey(preKeyEntity);
	}

	@Override
	public boolean containsPreKey(int preKeyId) {
		return preKeyDao.getPreKey(preKeyId) != null;
	}

	@Override
	public void removePreKey(int preKeyId) {
		preKeyDao.deletePreKey(preKeyId);
	}

	public void markPreKeyAsUsed(int preKeyId) {
		SignalPreKeyEntity entity = preKeyDao.getPreKey(preKeyId);
		if (entity != null) {
			entity.used = true;
			preKeyDao.updatePreKey(entity);
		}
	}

	/** Count unused pre-keys. */
	public int getUnusedPreKeyCount() {
		return preKeyDao.countUnusedPreKeys();
	}

	/** Get next usable pre-keys. */
	public PreKeyRecord getNextUnusedPreKey() throws InvalidKeyIdException {
		SignalPreKeyEntity entity = preKeyDao.getUnusedPreKey();
		if (entity != null) {
			try {
				return new PreKeyRecord(entity.record);
			} catch (Exception e) {
				preKeyDao.deletePreKey(entity.keyId); // Drop the compromised key
				throw new InvalidKeyIdException(new RuntimeException("Corrupted unused PreKey found and removed: " + entity.keyId, e));
			}
		} else {
			throw new InvalidKeyIdException("No unused PreKey available.");
		}
	}
}