package org.sedo.satmesh.signal.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Entity for Signal Session */
@Entity(tableName = "signal_session")
public class SignalSessionEntity {
	@PrimaryKey
	@NonNull
	public final String address;

	public final byte[] record;

	public SignalSessionEntity(@NonNull String address, byte[] record) {
		this.address = address;
		this.record = record;
	}
}
