package org.sedo.satmesh.signal.model;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object (DAO) for managing {@link SignalSessionEntity} instances in the database.
 * Provides methods to query, insert, and delete signal session records.
 */
@Dao
public interface SignalSessionDao {
	/**
	 * Retrieves a signal session by its address.
	 *
	 * @param address The address of the session to retrieve (typically in the format "name.deviceId").
	 * @return The {@link SignalSessionEntity} if found, or null otherwise.
	 */
	@Query("SELECT * FROM signal_session WHERE address = :address")
	SignalSessionEntity getSession(String address);

	/**
	 * Inserts a signal session into the database. If a session with the same address
	 * already exists, it will be replaced.
	 *
	 * @param session The {@link SignalSessionEntity} to insert or replace.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insertSession(SignalSessionEntity session);

	/**
	 * Deletes a specific signal session by its address.
	 *
	 * @param address The address of the session to delete.
	 */
	@Query("DELETE FROM signal_session WHERE address = :address")
	void deleteSession(String address);

	/**
	 * Deletes all signal sessions associated with a given name (user).
	 * This matches addresses that start with the given name followed by a dot.
	 *
	 * @param name The name part of the session addresses to delete (e.g., "username").
	 */
	@Query("DELETE FROM signal_session WHERE address LIKE :name || '.%'")
	void deleteAllSessionsForName(String name);

	/**
	 * Retrieves a list of all session addresses associated with a given name prefix.
	 * This is useful for finding all devices a user has had a session with.
	 *
	 * @param name The name prefix to search for (e.g., "username").
	 * @return A list of session addresses (strings) that match the name prefix.
	 */
	@Query("SELECT address FROM signal_session WHERE address LIKE :name || '.%'")
	List<String> getSessionAddressesByNamePrefix(String name);

	/**
	 * Deletes all signal sessions from the database.
	 * Use with caution as this will remove all persisted session records.
	 */
	@Query("DELETE FROM signal_session")
	void clearAll();

	/**
	 * Filters a given list of addresses, returning a LiveData list of those addresses
	 * for which a secure signal session exists.
	 * This can be used to identify which of a list of potential contacts have
	 * established sessions.
	 *
	 * @param fromAddresses A list of addresses to check for existing sessions.
	 * @return A {@link LiveData} list of strings, containing only the addresses from the input
	 * list that have a corresponding session record in the database.
	 */
	@Query("SELECT address FROM signal_session WHERE address IN (:fromAddresses)")
	LiveData<List<String>> filterSecuredSessionAddresses(List<String> fromAddresses);
}
