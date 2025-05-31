package org.sedo.satmesh.signal.store;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.signal.model.SignalIdentityKeyDao;
import org.sedo.satmesh.signal.model.SignalIdentityKeyEntity;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.IdentityKeyStore;

import java.util.Arrays;

public class AndroidIdentityKeyStore implements IdentityKeyStore {
	private final SignalIdentityKeyDao identityKeyDao;
	private final IdentityKeyPair identityKeyPair;
	private final int registrationId;

	private static AndroidIdentityKeyStore INSTANCE;

	public static AndroidIdentityKeyStore getInstance(IdentityKeyPair identityKeyPair, int registrationId){
		if (INSTANCE == null){
			synchronized (AndroidIdentityKeyStore.class){
				// At least the database must be initialized by the main activity
				AppDatabase db = AppDatabase.getDB(null);
				if (INSTANCE == null && db != null){
					INSTANCE = new AndroidIdentityKeyStore(db.identityKeyDao(), identityKeyPair, registrationId);
				}
			}
		}
		return INSTANCE;
	}

	protected AndroidIdentityKeyStore(SignalIdentityKeyDao identityKeyDao,
	                               IdentityKeyPair identityKeyPair,
	                               int registrationId) {
		this.identityKeyDao = identityKeyDao;
		this.identityKeyPair = identityKeyPair;
		this.registrationId = registrationId;
	}

	@Override
	public IdentityKeyPair getIdentityKeyPair() {
		return identityKeyPair;
	}

	@Override
	public int getLocalRegistrationId() {
		return registrationId;
	}

	@Override
	public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
		String addressString = address.getName() + "." + address.getDeviceId();
		SignalIdentityKeyEntity existing = identityKeyDao.getIdentityKey(addressString);

		if (existing != null && !Arrays.equals(existing.identityKey, identityKey.serialize())) {
			// La clé d'identité a changé - cela pourrait indiquer une attaque
			return false;
		}

		SignalIdentityKeyEntity identityKeyEntity = new SignalIdentityKeyEntity(addressString, identityKey.serialize());
		identityKeyDao.insertIdentityKey(identityKeyEntity);
		return true;
	}

	@Override
	public boolean isTrustedIdentity(SignalProtocolAddress address,
	                                 IdentityKey identityKey,
	                                 Direction direction) {
		String addressString = address.getName() + "." + address.getDeviceId();
		SignalIdentityKeyEntity existing = identityKeyDao.getIdentityKey(addressString);

		return existing == null || Arrays.equals(existing.identityKey, identityKey.serialize());
	}

	@Override
	public IdentityKey getIdentity(SignalProtocolAddress address) {
		String addressString = address.getName() + "." + address.getDeviceId();
		SignalIdentityKeyEntity identityKeyEntity = identityKeyDao.getIdentityKey(addressString);

		if (identityKeyEntity != null) {
			try {
				return new IdentityKey(identityKeyEntity.identityKey, 0);
			} catch (Exception e) {
				return null;
			}
		}
		return null;
	}
}