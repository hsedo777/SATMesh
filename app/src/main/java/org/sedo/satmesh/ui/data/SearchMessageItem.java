package org.sedo.satmesh.ui.data;

import androidx.annotation.NonNull;
import androidx.room.Embedded;

import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.Node;

import java.util.Objects;

/**
 * POJO (Plain Old Java Object) representing a message item in search results,
 * designed to be populated directly from a Room database query.
 * It associates a Message with its corresponding remote Node.
 */
public class SearchMessageItem {

	@Embedded
	@NonNull
	public Message message;

	@Embedded(prefix = "remoteNode_")
	@NonNull
	public Node remoteNode;

	public SearchMessageItem(@NonNull Message message, @NonNull Node remoteNode) {
		this.message = message;
		this.remoteNode = remoteNode;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		SearchMessageItem that = (SearchMessageItem) o;
		return message.equals(that.message) &&
				remoteNode.equals(that.remoteNode);
	}

	@Override
	public int hashCode() {
		return Objects.hash(message, remoteNode);
	}
}