package org.sedo.satmesh.signal.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Entity for Signal Session */
@Entity(tableName = "signal_session")
public class SignalSessionEntity {
	@PrimaryKey
	@NonNull
	public String address;

	public byte[] record;

	public SignalSessionEntity(String address, byte[] record) {
		this.address = address;
		this.record = record;
	}
}
