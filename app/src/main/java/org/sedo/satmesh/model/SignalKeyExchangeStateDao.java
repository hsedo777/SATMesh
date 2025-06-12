package org.sedo.satmesh.model;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object (DAO) for the {@link SignalKeyExchangeState} entity.
 * Provides methods for interacting with the signal_key_exchange_states table in the database.
 */
@Dao
public interface SignalKeyExchangeStateDao {

	/**
	 * Inserts a new SignalKeyExchangeState into the database.
	 * If a state with the same remoteAddress already exists, it will be replaced.
	 *
	 * @param state The SignalKeyExchangeState to insert.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insert(SignalKeyExchangeState state);

	/**
	 * Updates an existing SignalKeyExchangeState in the database.
	 *
	 * @param state The SignalKeyExchangeState to update.
	 */
	@Update
	void update(SignalKeyExchangeState state);

	/**
	 * Retrieves a LiveData object for the SignalKeyExchangeState associated with a given remote address.
	 * This allows observing changes to the state in real-time.
	 *
	 * @param remoteAddress The Signal Protocol address name of the remote node.
	 * @return LiveData containing the SignalKeyExchangeState, or null if not found.
	 */
	@Query("SELECT * FROM signal_key_exchange_states WHERE remote_address = :remoteAddress LIMIT 1")
	LiveData<SignalKeyExchangeState> getByRemoteAddressLiveData(String remoteAddress);

	/**
	 * Retrieves a SignalKeyExchangeState associated with a given remote address synchronously.
	 *
	 * @param remoteAddress The Signal Protocol address name of the remote node.
	 * @return The SignalKeyExchangeState, or null if not found.
	 */
	@Query("SELECT * FROM signal_key_exchange_states WHERE remote_address = :remoteAddress LIMIT 1")
	SignalKeyExchangeState getByRemoteAddress(String remoteAddress);

	/**
	 * Deletes a specific SignalKeyExchangeState from the database.
	 *
	 * @param remoteAddress The SignalKeyExchangeState to delete.
	 */
	@Query("DELETE FROM signal_key_exchange_states WHERE remote_address = :remoteAddress")
	void deleteByRemoteAddress(String remoteAddress);

	/**
	 * Retrieves all SignalKeyExchangeState entries from the database.
	 * @return A list of all SignalKeyExchangeState.
	 */
	@Query("SELECT * FROM signal_key_exchange_states")
	LiveData<List<SignalKeyExchangeState>> getAllStates();
}