package org.sedo.satmesh.signal.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface SignalPreKeyDao {
	@Query("SELECT * FROM signal_prekeys WHERE keyId = :keyId")
	SignalPreKeyEntity getPreKey(int keyId);

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insertPreKey(SignalPreKeyEntity preKey);

	@Query("DELETE FROM signal_prekeys WHERE keyId = :keyId")
	void deletePreKey(int keyId);

	@Query("SELECT * FROM signal_prekeys WHERE used = 0 ORDER BY keyId ASC LIMIT 1")
	SignalPreKeyEntity getUnusedPreKey();

	@Query("SELECT COUNT(*) FROM signal_prekeys WHERE used = 0")
	int countUnusedPreKeys();

	@Update
	void updatePreKey(SignalPreKeyEntity preKey);

	@Query("DELETE FROM signal_prekeys")
	void clearAll();
}
