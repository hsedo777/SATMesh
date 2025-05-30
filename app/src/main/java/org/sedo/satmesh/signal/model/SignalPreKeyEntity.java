package org.sedo.satmesh.signal.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "signal_prekeys")
public class SignalPreKeyEntity {
	@PrimaryKey
	public int keyId;

	public byte[] record;

	public boolean used;

	public SignalPreKeyEntity(int keyId, byte[] record, boolean used) {
		this.keyId = keyId;
		this.record = record;
		this.used = used;
	}
}
