package org.sedo.satmesh.model;


import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import org.sedo.satmesh.proto.PersonalInfo;

import java.util.Objects;

/**
 * Represents a node (device) in the mesh network.
 * Each node corresponds to a unique participant identified by its Signal Protocol address.
 */
@Entity(tableName = "node", indices = {@Index(value = "addressName", unique = true)})
public class Node {
	@PrimaryKey(autoGenerate = true)
	private Long id;
	private String displayName;
	/**
	 * The unique identifier for this node, derived from the {@code SignalProtocolAddress.name}.
	 * This ensures consistency across sessions and network disconnections.
	 */
	private String addressName;

	private boolean trusted = false;

	/**
	 * Populates the node's properties using a PersonalInfo Protocol Buffer object.
	 * This is typically used when receiving personal information from another node.
	 *
	 * @param info The PersonalInfo object containing the node's address name and display name.
	 */
	public void setPersonalInfo(@NonNull PersonalInfo info) {
		this.addressName = info.getAddressName();
		this.displayName = info.getDisplayName();
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getAddressName() {
		return addressName;
	}

	public void setAddressName(String addressName) {
		this.addressName = addressName;
	}

	public boolean isTrusted() {
		return trusted;
	}

	public void setTrusted(boolean trusted) {
		this.trusted = trusted;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Node node = (Node) o;
		return trusted == node.trusted && Objects.equals(id, node.id) && Objects.equals(displayName, node.displayName) && Objects.equals(addressName, node.addressName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, displayName, addressName, trusted);
	}
}
