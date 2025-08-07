package org.sedo.satmesh.signal.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "signal_identity_keys")
public record SignalIdentityKeyEntity(@PrimaryKey @NonNull String address, byte[] identityKey) {
}
