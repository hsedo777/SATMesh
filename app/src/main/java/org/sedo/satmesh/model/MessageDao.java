package org.sedo.satmesh.model;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import org.sedo.satmesh.ui.data.ChatListItem;

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
	 *
	 * @param message The Message object to insert.
	 * @return The row ID of the newly inserted Message.
	 */
	@Insert
	long insert(Message message);

	/**
	 * Updates an existing Message in the database. This is typically used to update
	 * the status of a message (e.g., from PENDING to DELIVERED or READ).
	 *
	 * @param message The Message object to update.
	 */
	@Update
	void update(Message message);

	/**
	 * Delete one message from database.
	 *
	 * @param message the message to be deleted
	 */
	@Delete
	void delete(Message message);

	/**
	 * Retrieves a single message by its primary key ID.
	 *
	 * @param id The primary key ID of the message.
	 * @return The Message object, or null if not found.
	 */
	@Query("SELECT * FROM message WHERE id = :id")
	Message getMessageByIdSync(Long id);

	/**
	 * Retrieves a message by its payload ID.
	 * This is useful for finding a specific message to update its status
	 * when an acknowledgement (ACK) is received.
	 *
	 * @param payloadId The unique payload ID of the message.
	 * @return The Message object, or null if not found.
	 */
	@Query("SELECT * FROM message WHERE payloadId = :payloadId")
	Message getMessageByPayloadIdSync(Long payloadId);


	/**
	 * Retrieves all messages exchanged between two specific nodes (users), ordered by timestamp.
	 * This query is designed for displaying a chat conversation.
	 * It fetches messages where the sender is A and recipient is B, OR sender is B and recipient is A.
	 *
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
	 * Retrieves all messages currently in a specific statuses from a specific sender.
	 * This is useful for retrying message delivery sending ack.
	 *
	 * @param senderNodeId The ID of the sender Node for which to retrieve messages.
	 * @param statuses     The statuses of the messages to retrieve (e.g., Message.MESSAGE_STATUS_FAILED).
	 * @return A list of messages matching the criteria.
	 */
	@Query("SELECT * FROM message WHERE senderNodeId = :senderNodeId AND status IN (:statuses) ORDER BY timestamp ASC")
	List<Message> getMessagesInStatusesFromSenderSync(Long senderNodeId, List<Integer> statuses);

	/**
	 * Retrieves a list of messages with a specific statuses for a given recipient node ID.
	 * This method is synchronous (blocking) and should be called from a background thread.
	 *
	 * @param recipientNodeId The ID of the recipient node.
	 * @param statuses        The statuses of the messages to retrieve (e.g., Message.MESSAGE_STATUS_FAILED).
	 * @return A list of messages matching the criteria.
	 */
	@Query("SELECT * FROM message WHERE recipientNodeId = :recipientNodeId AND status IN (:statuses) ORDER BY timestamp ASC")
	List<Message> getMessagesInStatusesForRecipientSync(Long recipientNodeId, List<Integer> statuses);

	/**
	 * Deletes list of message by their primary key ID.
	 *
	 * @param ids The list of primary keys ID of the messages to delete.
	 */
	@Query("DELETE FROM message WHERE id IN (:ids)")
	void deleteMessagesById(List<Long> ids);

	/**
	 * Deletes all messages exchanged with a specific node.
	 * This is useful when deleting a contact's conversation history.
	 *
	 * @param nodeId The ID of the node whose messages are to be deleted (as sender or recipient).
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM message WHERE senderNodeId = :nodeId OR recipientNodeId = :nodeId")
	void deleteMessagesWithNode(Long nodeId);

	/**
	 * Deletes all messages from the database.
	 */
	@Query("DELETE FROM message")
	void deleteAllMessages();

	/**
	 * Searches for messages using Full-Text Search (FTS) on their content.
	 * Results can be filtered to conversations involving the local host.
	 * FTS allows for more advanced search capabilities (e.g., stemming, ranking).
	 *
	 * @param query The search query string. FTS queries can be more complex (e.g., "word1 AND word2").
	 * @return A LiveData list of Message objects that match the FTS query, ordered by relevance.
	 */
	@Query("SELECT m.* FROM message AS m JOIN message_fts AS fts " +
			"ON m.id = fts.rowid " +
			"AND fts.content MATCH :query " +
			"ORDER BY bm25(matchinfo(message_fts)) DESC")
	// ORDER BY m.timestamp DESC
	LiveData<List<Message>> searchMessagesByContentFts(String query);

	/**
	 * Count the total number of messages in database
	 */
	@Query("SELECT COUNT(id) FROM message")
	long countAll();

	/**
	 * Retrieves a LiveData list of ChatListItem objects, each representing a distinct chat conversation.
	 * Each item includes:
	 * - The remote Node involved in the conversation, using its actual defined columns.
	 * - The latest Message exchanged in that conversation, using its actual defined columns.
	 * - The count of unread messages for that specific conversation (messages received by the host).
	 * <p>
	 * The list is ordered by the timestamp of the latest message, with the most recent conversations first.
	 *
	 * @param hostNodeId The ID of the local (host) node for which to retrieve conversations.
	 * @return A LiveData object containing a list of ChatListItem.
	 */
	@Query("SELECT " +
			"  N.id AS remote_node_id, " +
			"  N.displayName AS remote_node_displayName, " +
			"  N.addressName AS remote_node_addressName, " +
			"  N.trusted AS remote_node_trusted, " +
			"  N.lastSeen AS remote_node_lastSeen, " +
			"  N.connected AS remote_node_connected, " +
			"  M.id AS last_msg_id, " +
			"  M.payloadId AS last_msg_payloadId, " +
			"  M.content AS last_msg_content, " +
			"  M.timestamp AS last_msg_timestamp, " +
			"  M.status AS last_msg_status, " +
			"  M.type AS last_msg_type, " +
			"  M.senderNodeId AS last_msg_senderNodeId, " +
			"  M.recipientNodeId AS last_msg_recipientNodeId, " +
			"  COALESCE(UC.unreadCount, 0) AS unreadCount " +
			"FROM message AS M " +
			"JOIN ( " +
			"  SELECT " +
			"    CASE " +
			"      WHEN senderNodeId = :hostNodeId THEN recipientNodeId " +
			"      ELSE senderNodeId " +
			"    END AS partnerId, " +
			"    MAX(timestamp) AS latestTimestamp " +
			"  FROM message " +
			"  WHERE senderNodeId = :hostNodeId OR recipientNodeId = :hostNodeId " +
			"  GROUP BY partnerId " +
			") AS LM ON ( " +
			"     ((M.senderNodeId = :hostNodeId AND M.recipientNodeId = LM.partnerId) " +
			"   OR (M.recipientNodeId = :hostNodeId AND M.senderNodeId = LM.partnerId)) " +
			"   AND M.timestamp = LM.latestTimestamp " +
			") " +
			"JOIN node AS N ON N.id = LM.partnerId " +
			"LEFT JOIN ( " +
			"  SELECT " +
			"    CASE " +
			"      WHEN senderNodeId = :hostNodeId THEN recipientNodeId " +
			"      ELSE senderNodeId " +
			"    END AS partner_id_for_unread, " +
			"    COUNT(id) AS unreadCount " +
			"  FROM message " +
			"  WHERE (senderNodeId = :hostNodeId OR recipientNodeId = :hostNodeId) " +
			"    AND status != " + Message.MESSAGE_STATUS_READ + " " +
			"    AND recipientNodeId = :hostNodeId " +
			"  GROUP BY partner_id_for_unread " +
			") AS UC ON UC.partner_id_for_unread = LM.partnerId " +
			"ORDER BY M.timestamp DESC")
	LiveData<List<ChatListItem>> getChatListItems(Long hostNodeId);

}