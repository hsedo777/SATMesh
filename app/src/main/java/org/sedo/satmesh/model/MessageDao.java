package org.sedo.satmesh.model;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object (DAO) for the {@link Message} entity.
 * Provides methods for interacting with the 'message' table in the database.
 */
@Dao
public interface MessageDao {

	/**
	 * Inserts a new Message into the database. If a message with the same payloadId
	 * from the same sender and to the same recipient already exists, it will be
	 * aborted (OnConflictStrategy.ABORT).
	 * @param message The Message object to insert.
	 * @return The row ID of the newly inserted Message.
	 */
	@Insert
	long insert(Message message);

	/**
	 * Updates an existing Message in the database. This is typically used to update
	 * the status of a message (e.g., from PENDING to DELIVERED or READ).
	 * @param message The Message object to update.
	 * @return The number of rows updated.
	 */
	@Update
	int update(Message message);

	/**
	 * Retrieves a single message by its primary key ID.
	 * @param id The primary key ID of the message.
	 * @return The Message object, or null if not found.
	 */
	@Query("SELECT * FROM message WHERE id = :id")
	Message getMessageById(Long id);

	/**
	 * Retrieves a message by its payload ID, sender, and recipient.
	 * This is useful for finding a specific message to update its status
	 * when an acknowledgement (ACK) is received.
	 * @param payloadId The unique payload ID of the message.
	 * @param senderNodeId The ID of the sender Node.
	 * @param recipientNodeId The ID of the recipient Node.
	 * @return The Message object, or null if not found.
	 */
	@Query("SELECT * FROM message WHERE payloadId = :payloadId AND senderNodeId = :senderNodeId AND recipientNodeId = :recipientNodeId LIMIT 1")
	Message getMessageByPayloadIdAndParticipants(Long payloadId, Long senderNodeId, Long recipientNodeId);


	/**
	 * Retrieves all messages exchanged between two specific nodes (users), ordered by timestamp.
	 * This query is designed for displaying a chat conversation.
	 * It fetches messages where the sender is A and recipient is B, OR sender is B and recipient is A.
	 * @param nodeId1 The ID of the first node (e.g., local host node ID).
	 * @param nodeId2 The ID of the second node (e.g., contact node ID).
	 * @return A LiveData list of Message objects representing the conversation history.
	 */
	@Query("SELECT * FROM message " +
			"WHERE (senderNodeId = :nodeId1 AND recipientNodeId = :nodeId2) " +
			"OR (senderNodeId = :nodeId2 AND recipientNodeId = :nodeId1) " +
			"ORDER BY timestamp ASC")
	LiveData<List<Message>> getConversationMessages(Long nodeId1, Long nodeId2);

	/**
	 * Retrieves all messages currently in a pending or routing status for a specific recipient.
	 * This is useful for retrying message delivery.
	 * @param recipientNodeId The ID of the recipient Node for which to retrieve pending messages.
	 * @return A list of pending or routing Message objects.
	 */
	@Query("SELECT * FROM message WHERE recipientNodeId = :recipientNodeId AND (status = " +
			Message.MESSAGE_STATUS_PENDING + " OR status = " + Message.MESSAGE_STATUS_FAILED + ") " +
			"ORDER BY timestamp ASC")
	List<Message> getPendingMessagesForRecipient(Long recipientNodeId);


	/**
	 * Deletes a specific message by its primary key ID.
	 * @param id The primary key ID of the message to delete.
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM message WHERE id = :id")
	int deleteMessageById(Long id);

	/**
	 * Deletes all messages exchanged with a specific node.
	 * This is useful when deleting a contact's conversation history.
	 * @param nodeId The ID of the node whose messages are to be deleted (as sender or recipient).
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM message WHERE senderNodeId = :nodeId OR recipientNodeId = :nodeId")
	int deleteMessagesWithNode(Long nodeId);

	/**
	 * Deletes all messages from the database.
	 */
	@Query("DELETE FROM message")
	void deleteAllMessages();

	/**
	 * Searches for messages using Full-Text Search (FTS) on their content.
	 * Results can be filtered to conversations involving the local host.
	 * FTS allows for more advanced search capabilities (e.g., stemming, ranking).
	 * @param query The search query string. FTS queries can be more complex (e.g., "word1 AND word2").
	 * @return A LiveData list of Message objects that match the FTS query, ordered by relevance.
	 */
	@Query("SELECT m.* FROM message AS m JOIN message_fts AS fts " +
			"ON m.id = fts.rowid " +
			"AND fts.content MATCH :query " +
			"ORDER BY bm25(matchinfo(message_fts)) DESC") // ORDER BY m.timestamp DESC
	LiveData<List<Message>> searchMessagesByContentFts(String query);
}