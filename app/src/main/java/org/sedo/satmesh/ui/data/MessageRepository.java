package org.sedo.satmesh.ui.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import org.jetbrains.annotations.NotNull;
import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.MessageDao;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Repository class that abstracts access to the Message data source (Room).
 * Acts as an intermediary between the ViewModel and DAO to promote separation of concerns.
 */
public class MessageRepository {

	private static final String TAG = "MessageRepository";
	private final MessageDao messageDao;
	private final Executor executor;

	public MessageRepository(@NonNull Context context) {
		AppDatabase db = AppDatabase.getDB(context);
		this.messageDao = db.messageDao();
		executor = db.getQueryExecutor();
	}

	/**
	 * Inserts a message into the database.
	 * The callback will be invoked on the main thread after the operation.
	 *
	 * @param message  The message to insert.
	 * @param callback A consumer to receive the success status (true for success, false for failure).
	 */
	public void insertMessage(Message message, @Nullable Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				message.setId(messageDao.insert(message));
				Log.i(TAG, "Inserted message with ID: " + message.getId());
				if (callback != null)
					callback.accept(true);
			} catch (Exception e) {
				Log.w(TAG, "Error inserting message", e);
				if (callback != null)
					callback.accept(false);
			}
		});
	}

	/**
	 * Updates an existing message in the database.
	 * This method is executed in an executor thread
	 *
	 * @param message The message to update.
	 */
	public void updateMessage(Message message) {
		executor.execute(() -> messageDao.update(message));
	}

	/**
	 * Updates an existing message in the database.
	 * This method is executed in an executor thread
	 *
	 * @param message  The message to update.
	 * @param callback A consumer to receive the success status
	 *                 (true for success, false for failure).
	 */
	public void updateMessage(Message message, @NotNull Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				messageDao.update(message);
				Log.i(TAG, "Updated message with ID: " + message.getId());
				callback.accept(true);
			} catch (Exception e) {
				Log.e(TAG, "Error updating message", e);
				callback.accept(false);
			}
		});
	}

	/**
	 * Retrieves live data of messages for a specific conversation between two nodes.
	 *
	 * @param hostNodeId   The ID of the local node.
	 * @param remoteNodeId The ID of the remote node.
	 * @return LiveData containing a list of messages for the conversation.
	 */
	public LiveData<List<Message>> getConversationMessages(Long hostNodeId, Long remoteNodeId) {
		return messageDao.getConversationMessages(hostNodeId, remoteNodeId);
	}

	public Message getMessageByIdSync(long messageId) {
		return messageDao.getMessageByIdSync(messageId);
	}

	/**
	 * Returns a message based on its payload ID and participants.
	 * This method is synchronous and should be called from a background thread.
	 */
	public Message getMessageByPayloadIdSync(Long payloadId) {
		return messageDao.getMessageByPayloadIdSync(payloadId);
	}

	/**
	 * Delegated to {@link MessageDao#isMessageRead(long)}
	 */
	public boolean isMessageRead(long payloadId) {
		return messageDao.isMessageRead(payloadId);
	}

	/**
	 * Retrieves messages from a specific sender and in a specific statues.
	 * This method is synchronous and should be called from a background thread.
	 *
	 * @see MessageDao#getMessagesInStatusesFromSenderSync(Long, List)
	 */
	public List<Message> getMessagesInStatusesFromSenderSync(Long senderNodeId, List<Integer> statues) {
		return messageDao.getMessagesInStatusesFromSenderSync(senderNodeId, statues);
	}

	/**
	 * Retrieves a list of messages with a specific statues for a given recipient node ID.
	 * This method performs a synchronous database query and should be called from a background thread.
	 *
	 * @param recipientNodeId The ID of the recipient node.
	 * @param statues         The statues of the messages to retrieve (e.g., Message.MESSAGE_STATUS_FAILED).
	 * @return A list of messages matching the criteria. Returns an empty list if no messages are found or on error.
	 */
	public List<Message> getMessagesInStatusesForRecipientSync(Long recipientNodeId, List<Integer> statues) {
		return messageDao.getMessagesInStatusesForRecipientSync(recipientNodeId, statues);
	}

	/**
	 * Retrieves a LiveData list of ChatListItem objects, representing active chat conversations.
	 * Each item includes the remote node, the latest message, and the unread message count.
	 * The data is observed from the database and will update automatically.
	 *
	 * @param hostNodeId The ID of the local (host) node to filter conversations for.
	 * @return A LiveData object containing a list of ChatListItem.
	 */
	public LiveData<List<ChatListItem>> getChatListItems(Long hostNodeId) {
		return messageDao.getChatListItems(hostNodeId);
	}

	/**
	 * Delegated to {@link MessageDao#deleteMessagesById(List)}
	 * This method is executed on its executor thread.
	 */
	public void deleteMessagesById(List<Long> ids) {
		executor.execute(() -> messageDao.deleteMessagesById(ids));
	}

	/**
	 * Delegated to {@link MessageDao#deleteMessagesWithNode(Long)}
	 * This method is executed on caller's thread
	 */
	public void deleteMessagesWithNode(Long nodeId) {
		executor.execute(() -> messageDao.deleteMessagesWithNode(nodeId));
	}

	/**
	 * Delegated to methode {@link MessageDao#searchMessagesByContentFts(String, long)}
	 */
	public LiveData<List<SearchMessageItem>> searchMessagesByContentFts(@NonNull String query, long hostNodeId) {
		return messageDao.searchMessagesByContentFts(query, hostNodeId);
	}

	/**
	 * Delegated method to {@link MessageDao#searchMessagesByContentFts(String, long)}
	 */
	public LiveData<List<ChatListItem>> searchDiscussions(long hostNodeId, String query) {
		return messageDao.searchDiscussions(hostNodeId, query);
	}
}
