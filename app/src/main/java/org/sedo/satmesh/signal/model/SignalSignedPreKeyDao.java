package org.sedo.satmesh.signal.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SignalSignedPreKeyDao {
	@Query("SELECT * FROM signal_signed_prekeys WHERE keyId = :keyId")
	SignalSignedPreKeyEntity getSignedPreKey(int keyId);

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insertSignedPreKey(SignalSignedPreKeyEntity signedPreKey);

	@Query("DELETE FROM signal_signed_prekeys WHERE keyId = :keyId")
	void deleteSignedPreKey(int keyId);

	@Query("SELECT * FROM signal_signed_prekeys")
	List<SignalSignedPreKeyEntity> getAllSignedPreKeys();

	// Get the latest SignedPreKey, assumed to be the current active
	@Query("SELECT * FROM signal_signed_prekeys ORDER BY keyId DESC LIMIT 1")
	SignalSignedPreKeyEntity getLatestSignedPreKey();

	@Query("DELETE FROM signal_signed_prekeys")
	void clearAll();
}
