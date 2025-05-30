package org.sedo.satmesh.signal.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SignalSessionDao {
	@Query("SELECT * FROM signal_session WHERE address = :address")
	SignalSessionEntity getSession(String address);

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insertSession(SignalSessionEntity session);

	@Query("DELETE FROM signal_session WHERE address = :address")
	void deleteSession(String address);

	@Query("SELECT address FROM signal_session")
	List<String> getAllSessionAddresses();

	@Query("DELETE FROM signal_session WHERE address LIKE :name || '.%'")
	void deleteAllSessionsForName(String name);

	@Query("SELECT address FROM signal_session WHERE address LIKE :name || '.%'")
	List<String> getSessionAddressesByNamePrefix(String name);

	@Query("SELECT * FROM signal_session WHERE address IN (:addresses)")
	List<SignalSessionEntity> getSessionsByAddresses(List<String> addresses);
}
