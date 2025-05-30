package org.sedo.satmesh.signal.store;

import org.sedo.satmesh.signal.model.SignalSessionDao;
import org.sedo.satmesh.signal.model.SignalSessionEntity;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionStore;

import java.util.ArrayList;
import java.util.List;

/** Implementation of session store. */
public class AndroidSessionStore implements SessionStore {

	private static final int DEFAULT_PRIMARY_DEVICE_ID = 1;

	private final SignalSessionDao sessionDao;

	public AndroidSessionStore(SignalSessionDao sessionDao) {
		this.sessionDao = sessionDao;
	}

	private String getAddressKey(SignalProtocolAddress address) {
		return address.getName() + "." + address.getDeviceId();
	}

	@Override
	public SessionRecord loadSession(SignalProtocolAddress address) {
		String addressString = getAddressKey(address);
		SignalSessionEntity sessionEntity = sessionDao.getSession(addressString);

		if (sessionEntity != null) {
			try {
				return new SessionRecord(sessionEntity.record);
			} catch (Exception e) {
				//The record is compromised
				throw new RuntimeException("Fatal ! The session's public and private keys need to be reinitialized for " + addressString, e);
			}
		} else {
			return new SessionRecord();
		}
	}

	@Override
	public void storeSession(SignalProtocolAddress address, SessionRecord record) {
		String addressString = getAddressKey(address);
		SignalSessionEntity sessionEntity = new SignalSessionEntity(addressString, record.serialize());
		sessionDao.insertSession(sessionEntity);
	}

	@Override
	public boolean containsSession(SignalProtocolAddress address) {
		String addressString = getAddressKey(address);
		return sessionDao.getSession(addressString) != null;
	}

	@Override
	public void deleteSession(SignalProtocolAddress address) {
		String addressString = getAddressKey(address);
		sessionDao.deleteSession(addressString);
	}

	@Override
	public void deleteAllSessions(String name) {
		sessionDao.deleteAllSessionsForName(name);
	}

	@Override
	public List<Integer> getSubDeviceSessions(String name) {
		List<String> allAddresses = sessionDao.getSessionAddressesByNamePrefix(name);
		List<Integer> deviceIds = new ArrayList<>();

		for (String address : allAddresses) {
				try {
					int deviceId = Integer.parseInt(address.substring(name.length() + 1));
					if (deviceId != DEFAULT_PRIMARY_DEVICE_ID) { // Exclude the main device
						deviceIds.add(deviceId);
					}
				} catch (NumberFormatException ignore) {
					// Ignore malformed address
			}
		}
		return deviceIds;
	}
}