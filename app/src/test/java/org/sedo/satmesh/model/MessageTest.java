package org.sedo.satmesh.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class MessageTest {

	private final long currentTime = System.currentTimeMillis();
	private Message message;

	@Before
	public void setUp() {
		message = new Message();
	}

	@Test
	public void testDefaultConstructor() {
		assertNull("ID should be null by default", message.getId());
		assertNull("PayloadID should be null by default", message.getPayloadId());
		assertNull("Content should be null by default", message.getContent());
		assertEquals("Timestamp should be 0 by default", 0, message.getTimestamp());
		assertEquals("Status should be 0 by default", 0, message.getStatus());
		assertEquals("Type should be 0 by default", 0, message.getType());
		assertNull("SenderNodeId should be null by default", message.getSenderNodeId());
		assertNull("RecipientNodeId should be null by default", message.getRecipientNodeId());
		assertNull("LastSendingAttempt should be null by default", message.getLastSendingAttempt());
	}

	@Test
	public void testCopyConstructor() {
		Message original = new Message();
		original.setId(1L);
		original.setPayloadId(100L);
		original.setContent("Test Content");
		original.setTimestamp(currentTime);
		original.setStatus(Message.MESSAGE_STATUS_DELIVERED);
		original.setType(Message.MESSAGE_TYPE_TEXT);
		original.setSenderNodeId(2L);
		original.setRecipientNodeId(3L);
		original.setLastSendingAttempt(currentTime - 1000);

		Message copy = new Message(original);

		assertNotSame("Copied message should be a different instance", original, copy);
		assertEquals("ID should be copied", original.getId(), copy.getId());
		assertEquals("PayloadID should be copied", original.getPayloadId(), copy.getPayloadId());
		assertEquals("Content should be copied", original.getContent(), copy.getContent());
		assertEquals("Timestamp should be copied", original.getTimestamp(), copy.getTimestamp());
		assertEquals("Status should be copied", original.getStatus(), copy.getStatus());
		assertEquals("Type should be copied", original.getType(), copy.getType());
		assertEquals("SenderNodeId should be copied", original.getSenderNodeId(), copy.getSenderNodeId());
		assertEquals("RecipientNodeId should be copied", original.getRecipientNodeId(), copy.getRecipientNodeId());
		assertEquals("LastSendingAttempt should be copied", original.getLastSendingAttempt(), copy.getLastSendingAttempt());
	}

	@Test
	public void setPayloadId() {
		message.setPayloadId(0L); // Attempt to set to 0L
		assertNull("PayloadId should remain null if attempted to set to 0L initially", message.getPayloadId());

		Long initialPayloadId = 789L;
		message.setPayloadId(initialPayloadId);
		Long newPayloadId = 999L;
		message.setPayloadId(newPayloadId); // Attempt to modify
		assertEquals("PayloadId should not be modified if already set", initialPayloadId, message.getPayloadId());

	}

	@Test
	public void setContent() {
		String testContent = "Hello, SATMesh!";
		message.setContent(testContent);
		assertEquals("Getter should return the set content", testContent, message.getContent());
	}

	@Test
	public void isDelivered() {
		message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
		assertTrue("isDelivered should be true for DELIVERED status", message.isDelivered());

		message.setStatus(Message.MESSAGE_STATUS_PENDING);
		assertFalse("isDelivered should be false for non-DELIVERED status", message.isDelivered());
	}

	@Test
	public void isRead() {
		message.setStatus(Message.MESSAGE_STATUS_READ);
		assertTrue("isRead should be true for READ status", message.isRead());

		message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
		assertFalse("isRead should be false for non-READ status", message.isRead());
	}

	@Test
	public void hadReceivedAck() {
		message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
		assertTrue("hadReceivedAck should be true for DELIVERED status", message.hadReceivedAck());

		message.setStatus(Message.MESSAGE_STATUS_READ);
		assertTrue("hadReceivedAck should be true for READ status", message.hadReceivedAck());

		message.setStatus(Message.MESSAGE_STATUS_PENDING);
		assertFalse("hadReceivedAck should be false for PENDING status", message.hadReceivedAck());
	}

	@Test
	public void isOnTransmissionQueue() {
		message.setStatus(Message.MESSAGE_STATUS_PENDING);
		message.setLastSendingAttempt(currentTime);
		assertTrue("isOnTransmissionQueue should be true for PENDING and non-null attempt", message.isOnTransmissionQueue());

		message.setStatus(Message.MESSAGE_STATUS_DELIVERED);
		assertFalse("isOnTransmissionQueue should be false if status is not PENDING", message.isOnTransmissionQueue());

		message.setStatus(Message.MESSAGE_STATUS_PENDING);
		message.setLastSendingAttempt(null);
		assertFalse("isOnTransmissionQueue should be false if lastSendingAttempt is null", message.isOnTransmissionQueue());
	}

	@Test
	public void isSentTo() {
		Node recipient = new Node();
		Long recipientId = 55L;
		recipient.setId(recipientId);
		message.setRecipientNodeId(recipientId);
		assertTrue("isSentTo should be true if recipient IDs match", message.isSentTo(recipient));

		recipient.setId(55L);
		message.setRecipientNodeId(66L); // Different ID
		assertFalse("isSentTo should be false if recipient IDs differ", message.isSentTo(recipient));

		assertFalse("isSentTo should be false if recipient Node is null", message.isSentTo(null));

		recipient.setId(55L);
		message.setRecipientNodeId(null);
		assertFalse("isSentTo should be false if message's recipientNodeId is null", message.isSentTo(recipient));
	}

	@Test
	public void testEquals() {
		assertEquals("A message should be equal to itself", message, message);
		assertNotEquals("A message should not be equal to null", null, message);
		assertNotEquals("A message should not be equal to an object of different class", new Object(), message);

		Message msg1 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, currentTime);
		Message msg2 = createPopulatedMessage(16L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		// Note: ID and lastSendingAttempt are not part of equals in Message.java
		assertEquals("Identical messages should be equal", msg1, msg2);

		Message msg3 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg4 = createPopulatedMessage(1L, 101L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		assertNotEquals("Messages with different payloadId should not be equal", msg3, msg4);

		Message msg5 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg6 = createPopulatedMessage(1L, 100L, "World", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		assertNotEquals("Messages with different content should not be equal", msg5, msg6);

		Message msg7 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg8 = createPopulatedMessage(1L, 100L, "Hello", currentTime + 1, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		assertNotEquals("Messages with different timestamp should not be equal", msg7, msg8);

		Message msg9 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg10 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_DELIVERED, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		assertNotEquals("Messages with different status should not be equal", msg9, msg10);

		Message msg11 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg12 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_IMAGE, 2L, 3L, null);
		assertNotEquals("Messages with different type should not be equal", msg11, msg12);

		Message msg13 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg14 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 99L, 3L, null);
		assertNotEquals("Messages with different senderNodeId should not be equal", msg13, msg14);

		Message msg15 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg16 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 99L, null);
		assertNotEquals("Messages with different recipientNodeId should not be equal", msg15, msg16);

	}

	@Test
	public void testHashCode() {
		Message msg = createPopulatedMessage(1L, 100L, "Test", currentTime, Message.MESSAGE_STATUS_PENDING, Message.MESSAGE_TYPE_TEXT, 2L, 3L, currentTime);
		int initialHashCode = msg.hashCode();
		assertEquals("HashCode should be consistent for an unmodified object", initialHashCode, msg.hashCode());

		Message msg1 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg2 = createPopulatedMessage(1L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		assertEquals("HashCodes should be same for equal objects", msg1.hashCode(), msg2.hashCode());

		Message msg3 = createPopulatedMessage(13L, 100L, "Hello", currentTime, Message.MESSAGE_STATUS_SENT, Message.MESSAGE_TYPE_TEXT, 2L, 3L, null);
		Message msg4 = createPopulatedMessage(13L, 101L, "World", currentTime, Message.MESSAGE_STATUS_DELIVERED, Message.MESSAGE_TYPE_IMAGE, 3L, 4L, null);
		// It's not a strict requirement for unequal objects to have different hashCodes, but good hash functions usually achieve this.
		assertNotEquals("HashCodes for substantially different objects are likely different", msg4.hashCode(), msg3.hashCode());

	}

	// Helper method to create a populated Message for tests
	private Message createPopulatedMessage(
			Long id, Long payloadId, String content, long timestamp,
			int status, int type, Long senderId, Long recipientId, Long lastAttempt) {
		Message msg = new Message();
		msg.setId(id);
		msg.setPayloadId(payloadId); // Set payloadId first for its logic to work if it's null initially
		msg.setContent(content);
		msg.setTimestamp(timestamp);
		msg.setStatus(status);
		msg.setType(type);
		msg.setSenderNodeId(senderId);
		msg.setRecipientNodeId(recipientId);
		msg.setLastSendingAttempt(lastAttempt);
		return msg;
	}
}
