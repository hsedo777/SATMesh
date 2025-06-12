package org.sedo.satmesh.model;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.Objects;

/**
 * Represents the persistent state of a Signal Protocol key exchange process
 * with a specific remote node. This tracks whether our PreKeyBundle has been
 * sent to them and when their PreKeyBundle was last received and processed by us.
 * @author hovozounkou
 */
@Entity(tableName = "signal_key_exchange_states", indices = @Index(value = "remote_address", unique = true))
public class SignalKeyExchangeState {

	@PrimaryKey(autoGenerate = true)
	private Long id;

	/**
	 * The Signal Protocol address name of the remote node.
	 * This serves as the unique identifier for the key exchange state with a specific node.
	 */
	@NonNull
	@ColumnInfo(name = "remote_address")
	private String remoteAddress;

	/**
	 * Timestamp (in milliseconds) of the last time our local device
	 * attempted to send its PreKeyBundle to this remote node.
	 * A value of 0L indicates no attempt has been made or it needs to be re-sent.
	 */
	@ColumnInfo(name = "last_our_sent_attempt")
	private Long lastOurSentAttempt;

	/**
	 * Timestamp (in milliseconds) of the last time our local device
	 * successfully received and processed a PreKeyBundle from this remote node.
	 * A value of 0L indicates no PreKeyBundle has been received from them.
	 */
	@ColumnInfo(name = "last_their_received_attempt")
	private Long lastTheirReceivedAttempt;

	public SignalKeyExchangeState() {
	}

	@Ignore
	public SignalKeyExchangeState(@NonNull String remoteAddress) {
		this.remoteAddress = remoteAddress;
	}

	// Getters and Setters
	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	@NonNull
	public String getRemoteAddress() {
		return remoteAddress;
	}

	public void setRemoteAddress(@NonNull String remoteAddress) {
		this.remoteAddress = remoteAddress;
	}

	public Long getLastOurSentAttempt() {
		return lastOurSentAttempt;
	}

	public void setLastOurSentAttempt(Long lastOurSentAttempt) {
		this.lastOurSentAttempt = lastOurSentAttempt;
	}

	public Long getLastTheirReceivedAttempt() {
		return lastTheirReceivedAttempt;
	}

	public void setLastTheirReceivedAttempt(Long lastTheirReceivedAttempt) {
		this.lastTheirReceivedAttempt = lastTheirReceivedAttempt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		SignalKeyExchangeState that = (SignalKeyExchangeState) o;
		return remoteAddress.equals(that.remoteAddress) &&
				Objects.equals(lastOurSentAttempt, that.lastOurSentAttempt) &&
				Objects.equals(lastTheirReceivedAttempt, that.lastTheirReceivedAttempt);
	}

	@Override
	public int hashCode() {
		return Objects.hash(remoteAddress, lastOurSentAttempt, lastTheirReceivedAttempt);
	}
}