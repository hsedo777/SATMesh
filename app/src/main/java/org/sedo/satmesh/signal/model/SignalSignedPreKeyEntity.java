package org.sedo.satmesh.signal.model;

import androidx.room.PrimaryKey;
import androidx.room.Entity;

@Entity(tableName = "signal_signed_prekeys")
public class SignalSignedPreKeyEntity {
	@PrimaryKey
	public int keyId;

	public byte[] record;

	public SignalSignedPreKeyEntity(int keyId, byte[] record) {
		this.keyId = keyId;
		this.record = record;
	}
}
