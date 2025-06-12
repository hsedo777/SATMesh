package org.sedo.satmesh.signal.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "signal_identity_keys")
public class SignalIdentityKeyEntity {

	@PrimaryKey
	@NonNull
	public final String address;

	public final byte[] identityKey;

	public SignalIdentityKeyEntity(@NonNull String address, byte[] identityKey) {
		this.address = address;
		this.identityKey = identityKey;
	}
}
