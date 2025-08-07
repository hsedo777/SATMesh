package org.sedo.satmesh.signal.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Entity for Signal Session
 */
@Entity(tableName = "signal_session")
public record SignalSessionEntity(@PrimaryKey @NonNull String address, byte[] record) {
}
