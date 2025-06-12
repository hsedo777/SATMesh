package org.sedo.satmesh.ui.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

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

	private final MessageDao messageDao;
	private final Executor executor;

	public MessageRepository(@NonNull Context context) {
		AppDatabase db = AppDatabase.getDB(context);
		this.messageDao = db.messageDao();
		executor = db.getQueryExecutor();
	}

	/**
	 * Inserts a message asynchronously. and map
	 */
	public void insertMessage(Message message, @Nullable Consumer<Boolean> callback) {
		executor.execute(() -> {
			try{
				message.setId(messageDao.insert(message));
				if (callback != null)
					callback.accept(true);
			} catch (Exception e){
				if (callback != null)
					callback.accept(false);
			}
		});
	}

	/**
	 * Updates a message asynchronously.
	 */
	public void updateMessage(Message message) {
		executor.execute(() -> messageDao.update(message));
	}

	/**
	 * Returns LiveData containing the list of messages exchanged between two nodes.
	 * @param nodeId1 ID of the first node (host or remote)
	 * @param nodeId2 ID of the second node (host or remote)
	 */
	public LiveData<List<Message>> getConversationMessages(Long nodeId1, Long nodeId2) {
		return messageDao.getConversationMessages(nodeId1, nodeId2);
	}

	public Message getMessageById(long messageId){
		return getMessageById(messageId);
	}

	/**
	 * Returns a message based on its payload ID and participants.
	 * This method is synchronous and should be called from a background thread.
	 */
	public Message getMessageByPayloadId(Long payloadId) {
		return messageDao.getMessageByPayloadId(payloadId);
	}

	/**
	 * Retrieves pending or failed messages that are to be delivered to a given recipient.
	 * This method is synchronous and should be called from a background thread.
	 */
	public List<Message> getPendingMessagesForRecipient(Long recipientNodeId) {
		return messageDao.getPendingMessagesForRecipient(recipientNodeId);
	}
}
