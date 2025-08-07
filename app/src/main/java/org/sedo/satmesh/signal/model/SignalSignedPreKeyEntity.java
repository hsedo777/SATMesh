package org.sedo.satmesh.signal.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "signal_signed_prekeys")
public record SignalSignedPreKeyEntity(@PrimaryKey int keyId, byte[] record) {
}
