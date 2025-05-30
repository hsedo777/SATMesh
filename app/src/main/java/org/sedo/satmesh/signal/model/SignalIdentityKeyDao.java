package org.sedo.satmesh.signal.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface SignalIdentityKeyDao {
	@Query("SELECT * FROM signal_identity_keys WHERE address = :address")
	SignalIdentityKeyEntity getIdentityKey(String address);

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void insertIdentityKey(SignalIdentityKeyEntity identityKey);
}
