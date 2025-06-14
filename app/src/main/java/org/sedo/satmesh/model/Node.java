package org.sedo.satmesh.model;


import android.os.Bundle;

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
	private Long lastSeen;
	private boolean connected;

	private static final String NODE_ID = "node_id";
	private static final String NODE_DISPLAY_NAME = "node_display_name";
	private static final String NODE_ADDRESS_NAME = "node_address_name";
	private static final String NODE_TRUSTED = "arg_remote_node_name";

	/** Default constructor. */
	public Node(){}

	/** Constructor of copy. */
	public Node(@NonNull Node toCopy){
		id = toCopy.id;
		displayName = toCopy.displayName;
		addressName = toCopy.addressName;
		trusted = toCopy.trusted;
		lastSeen = toCopy.lastSeen;
		connected = toCopy.connected;
	}
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

	public PersonalInfo toPersonalInfo(boolean expectResult){
		return PersonalInfo.newBuilder().setAddressName(addressName).setExpectResult(expectResult).setDisplayName(displayName).build();
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

	@NonNull
	public String getNonNullName(){
		return displayName == null ? addressName : displayName;
	}

	public boolean isTrusted() {
		return trusted;
	}

	public void setTrusted(boolean trusted) {
		this.trusted = trusted;
	}

	public Long getLastSeen() {
		return lastSeen;
	}

	public void setLastSeen(Long lastSeen) {
		this.lastSeen = lastSeen;
	}

	public boolean isConnected() {
		return connected;
	}

	public void setConnected(boolean connected) {
		this.connected = connected;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Node node = (Node) o;
		return trusted == node.trusted && connected == node.connected && Objects.equals(displayName, node.displayName) && Objects.equals(addressName, node.addressName) && Objects.equals(lastSeen, node.lastSeen);
	}

	@Override
	public int hashCode() {
		return Objects.hash(displayName, addressName, trusted, lastSeen, connected);
	}

	public void write(@NonNull Bundle input, @NonNull String prefix){
		if (id != null){
			input.putLong(prefix + NODE_ID, id);
		}
		input.putString(prefix + NODE_DISPLAY_NAME, displayName);
		input.putString(prefix + NODE_ADDRESS_NAME, addressName);
		input.putBoolean(prefix + NODE_TRUSTED, trusted);
	}

	@NonNull
	public static Node restoreFromBundle(@NonNull Bundle bundle, @NonNull String prefix){
		Node node = new Node();
		long temp = bundle.getLong(prefix + NODE_ID, -1);
		if (temp != -1){
			node.id = temp;
		}
		node.displayName = bundle.getString(prefix + NODE_DISPLAY_NAME, null);
		node.addressName = bundle.getString(prefix + NODE_ADDRESS_NAME, null);
		node.trusted = bundle.getBoolean(prefix + NODE_TRUSTED);
		return node;
	}
}
