package org.sedo.satmesh.signal.store;

import static org.sedo.satmesh.ui.UiUtils.getAddressKey;
import static org.sedo.satmesh.utils.Constants.SIGNAL_PROTOCOL_DEVICE_ID;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.signal.model.SignalSessionDao;
import org.sedo.satmesh.signal.model.SignalSessionEntity;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An Android-specific implementation of the {@link SessionStore} interface for managing
 * Signal Protocol session records.
 * This class interacts with a database via {@link SignalSessionDao} to persist session data.
 * It follows a singleton pattern for managing a single instance throughout the application.
 *
 * @author hsedo777
 */
public class AndroidSessionStore implements SessionStore {

	private static final String TAG = "AndroidSessionStore";
	private static volatile AndroidSessionStore INSTANCE;
	private final SignalSessionDao sessionDao;

	/**
	 * Constructs a new AndroidSessionStore.
	 *
	 * @param sessionDao The Data Access Object for session data.
	 */
	private AndroidSessionStore(SignalSessionDao sessionDao) {
		this.sessionDao = sessionDao;
	}

	/**
	 * Retrieves the singleton instance of AndroidSessionStore.
	 * <p>
	 * The instance is lazily initialized. {@link AppDatabase} must be initialized
	 * (e.g., by the main activity) before this method is called for the first time
	 * to ensure the DAO can be created.
	 * </p>
	 *
	 * @return The singleton instance of AndroidSessionStore, or null if the database
	 * is not yet initialized when first called.
	 */
	public static AndroidSessionStore getInstance() {
		if (INSTANCE == null) {
			synchronized (AndroidSessionStore.class) {
				// At least the database must be initialized by the main activity
				AppDatabase db = AppDatabase.getDB(null);
				if (INSTANCE == null && db != null) {
					INSTANCE = new AndroidSessionStore(db.sessionDao());
				}
			}
		}
		return INSTANCE;
	}

	@Override
	public SessionRecord loadSession(SignalProtocolAddress address) {
		String addressString = getAddressKey(address);
		SignalSessionEntity sessionEntity = sessionDao.getSession(addressString);

		if (sessionEntity != null) {
			try {
				return new SessionRecord(sessionEntity.record());
			} catch (Exception e) {
				//The record is compromised
				Log.e(TAG, "Fatal ! The session's public and private keys need to be reinitialized for " + addressString, e);
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
				if (deviceId != SIGNAL_PROTOCOL_DEVICE_ID) { // Exclude the main device
					deviceIds.add(deviceId);
				}
			} catch (NumberFormatException e) {
				// Ignore malformed address
				Log.w(TAG, "Malformed address: " + address, e);
			}
		}
		return deviceIds;
	}

	/**
	 * Clears all Signal Protocol related cryptographic data from the database.
	 * This includes session records, pre-keys, signed pre-keys, and identity keys.
	 * This method should be used with caution as it will render existing secure sessions invalid.
	 */
	public void clearAllCryptographyData() {
		AppDatabase db = AppDatabase.getDB(null);
		if (db != null) {
			Log.d(TAG, "Clearing all cryptographic data from the database");
			db.sessionDao().clearAll();
			db.preKeyDao().clearAll();
			db.signedPreKeyDao().clearAll();
			db.identityKeyDao().clearAll();
		}
	}

	/**
	 * Filters a list of potential addresses and returns a LiveData list containing only the
	 * names of those addresses for which a secure Signal Protocol session exists.
	 * <p>
	 * The input addresses are expected to be in the format "name.deviceId".
	 * The output LiveData will contain a list of "name" strings.
	 * </p>
	 *
	 * @param fromAddresses A list of full Signal Protocol addresses (e.g., "username.1").
	 * @return A {@link LiveData} list of strings, containing only the names (e.g., "username")
	 * from the input list that have a corresponding session record in the database.
	 */
	@NonNull
	public LiveData<List<String>> filterSecuredSessionAddressNames(List<String> fromAddresses) {
		LiveData<List<String>> securedFullAddresses = sessionDao.filterSecuredSessionAddresses(fromAddresses);

		return Transformations.map(securedFullAddresses, fullAddresses -> {
			if (fullAddresses == null) {
				return new ArrayList<>();
			}
			return fullAddresses.stream()
					.map(fullAddress -> {
						// The address is stored as "name.deviceId"
						// We need to extract the "name" part.
						int lastDotIndex = fullAddress.lastIndexOf('.');
						if (lastDotIndex > 0) { // Ensure there is a dot and it's not the first character
							return fullAddress.substring(0, lastDotIndex);
						}
						// Should not happen with valid SignalProtocolAddress keys
						Log.e(TAG, "Invalid address format: " + fullAddress);
						return null;
					})
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
		});
	}
}
