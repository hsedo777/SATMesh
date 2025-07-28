package org.sedo.satmesh.ui.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Embedded;
import androidx.room.Ignore;

import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.Node;

import java.util.Objects;

/**
 * A data class representing a single chat conversation item to be displayed in the ChatListFragment.
 * It encapsulates the remote Node involved in the conversation, the latest Message in that conversation,
 * and the count of unread messages.
 */
public class ChatListItem {

	// Embed the remote Node entity.
	@Embedded(prefix = "remote_node_")
	// Prefix to avoid column name conflicts
	@NonNull // The remote node should always be present for a conversation item
	public Node remoteNode;

	// Embed the latest Message entity.
	@Embedded(prefix = "last_msg_") // Prefix to avoid column name conflicts
	@NonNull // The last message should always be present for a conversation item
	public Message lastMessage;

	// Unread messages count
	public int unreadCount;

	@Ignore
	@Nullable
	public NodeState nodeState;

	// Constructor required by Room to hydrate this POJO from a query result
	public ChatListItem(@NonNull Node remoteNode, @NonNull Message lastMessage, int unreadCount) {
		this.remoteNode = remoteNode;
		this.lastMessage = lastMessage;
		this.unreadCount = unreadCount;
	}

	// --- Helper / Delegated functions for display logic in ViewHolder ---

	/**
	 * Checks if the last message was sent by the current host user.
	 * This method assumes the hostNodeId is passed to the ViewHolder and is available for comparison.
	 *
	 * @param hostNodeId The ID of the currently logged-in host node.
	 * @return {@code true} if the last message was sent by the host, false otherwise.
	 */
	public boolean isLastMessageSentByHost(@NonNull Long hostNodeId) {
		return lastMessage.getSenderNodeId().equals(hostNodeId);
	}

	/**
	 * Provides the display name of the sender of the last message for the UI.
	 * Will be "You" if sent by host, or the remote node's display name otherwise.
	 * Requires hostNodeId to be passed from the Adapter.
	 *
	 * @param hostNodeId The ID of the currently logged-in host node.
	 * @param youString  The localized string for "You".
	 * @return The display name of the sender.
	 */
	public String getLastMessageSenderDisplayName(@NonNull Long hostNodeId, @NonNull String youString) {
		if (isLastMessageSentByHost(hostNodeId)) {
			return youString;
		} else {
			return remoteNode.getDisplayName();
		}
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ChatListItem that = (ChatListItem) o;
		// Compare remoteNode.getId() for item identity
		// Compare contents (remoteNode, lastMessage, unreadCount) for content changes
		return remoteNode.getId().equals(that.remoteNode.getId()) &&
				unreadCount == that.unreadCount &&
				lastMessage.equals(that.lastMessage) &&
				nodeState == that.nodeState &&
				remoteNode.equals(that.remoteNode);
	}

	@Override
	public int hashCode() {
		return Objects.hash(remoteNode, lastMessage, nodeState, unreadCount);
	}
}