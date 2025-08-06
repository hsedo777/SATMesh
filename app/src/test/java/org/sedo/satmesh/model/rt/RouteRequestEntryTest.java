package org.sedo.satmesh.model.rt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

public class RouteRequestEntryTest {

	private final String testUuid = UUID.randomUUID().toString();

	@Test
	public void constructor() {
		RouteRequestEntry entry = new RouteRequestEntry(testUuid);
		assertEquals("Request UUID should be set by constructor", testUuid, entry.getRequestUuid());
		assertNull("DestinationNodeLocalId should be null initially", entry.getDestinationNodeLocalId());
		assertNull("PreviousHopLocalId should be null initially", entry.getPreviousHopLocalId());
	}

	@Test
	public void setAndGetDestinationNodeLocalId() {
		RouteRequestEntry entry = new RouteRequestEntry(testUuid);
		Long destId = 123L;
		entry.setDestinationNodeLocalId(destId);
		assertEquals("Getter should return set DestinationNodeLocalId", destId, entry.getDestinationNodeLocalId());

		entry.setDestinationNodeLocalId(null);
		assertNull("Getter should return null for DestinationNodeLocalId", entry.getDestinationNodeLocalId());
	}

	@Test
	public void setAndGetPreviousHopLocalId() {
		RouteRequestEntry entry = new RouteRequestEntry(testUuid);
		Long prevHopId = 456L;
		entry.setPreviousHopLocalId(prevHopId);
		assertEquals("Getter should return set PreviousHopLocalId", prevHopId, entry.getPreviousHopLocalId());

		entry.setPreviousHopLocalId(null);
		assertNull("Getter should return null for PreviousHopLocalId", entry.getPreviousHopLocalId());
	}

	@Test
	public void getRequestUuid() {
		String specificUuid = UUID.randomUUID().toString();
		RouteRequestEntry entry = new RouteRequestEntry(specificUuid);
		assertEquals("getRequestUuid should return the value passed during construction", specificUuid, entry.getRequestUuid());
	}

	@Test
	public void testEquals() {
		RouteRequestEntry entry1 = new RouteRequestEntry(testUuid);
		entry1.setDestinationNodeLocalId(1L);
		entry1.setPreviousHopLocalId(2L);

		// Reflexivity
		assertEquals("An entry should be equal to itself", entry1, entry1);

		// Symmetry
		RouteRequestEntry entry2 = new RouteRequestEntry(testUuid);
		entry2.setDestinationNodeLocalId(1L);
		entry2.setPreviousHopLocalId(2L);
		assertEquals("Symmetric equality should hold", entry1, entry2);
		assertEquals("Symmetric equality should hold (reverse)", entry2, entry1);

		// Null inequality
		assertNotEquals("An entry should not be equal to null", null, entry1);

		// Different class inequality
		assertNotEquals("An entry should not be equal to an object of different class", new Object(), entry1);

		String commonUuid = UUID.randomUUID().toString();
		Long commonDestId = 100L;
		Long commonPrevHopId = 200L;

		RouteRequestEntry entry3 = new RouteRequestEntry(commonUuid);
		entry3.setDestinationNodeLocalId(commonDestId);
		entry3.setPreviousHopLocalId(commonPrevHopId);

		RouteRequestEntry entry4 = new RouteRequestEntry(commonUuid);
		entry4.setDestinationNodeLocalId(commonDestId);
		entry4.setPreviousHopLocalId(commonPrevHopId);

		assertEquals("Entries with identical values should be equal", entry3, entry4);

		RouteRequestEntry entry5 = new RouteRequestEntry(UUID.randomUUID().toString());
		RouteRequestEntry entry6 = new RouteRequestEntry(UUID.randomUUID().toString()); // Different UUID
		assertNotEquals("Entries with different requestUuid should not be equal", entry5, entry6);

		RouteRequestEntry entry7 = new RouteRequestEntry(testUuid);
		entry7.setDestinationNodeLocalId(1L);
		RouteRequestEntry entry8 = new RouteRequestEntry(testUuid);
		entry8.setDestinationNodeLocalId(2L); // Different DestinationNodeLocalId
		assertNotEquals("Entries with different destinationNodeLocalId should not be equal", entry7, entry8);

		RouteRequestEntry entry9 = new RouteRequestEntry(testUuid);
		entry9.setPreviousHopLocalId(1L);
		RouteRequestEntry entry10 = new RouteRequestEntry(testUuid);
		entry10.setPreviousHopLocalId(2L); // Different PreviousHopLocalId
		assertNotEquals("Entries with different previousHopLocalId should not be equal", entry9, entry10);

		RouteRequestEntry entry11 = new RouteRequestEntry(testUuid);
		entry11.setDestinationNodeLocalId(null);
		entry11.setPreviousHopLocalId(1L);

		RouteRequestEntry entry12 = new RouteRequestEntry(testUuid);
		entry12.setDestinationNodeLocalId(null);
		entry12.setPreviousHopLocalId(1L);
		assertEquals("Entries with same null fields should be equal", entry11, entry12);

		RouteRequestEntry entry13 = new RouteRequestEntry(testUuid);
		entry13.setDestinationNodeLocalId(1L); // Not null
		entry13.setPreviousHopLocalId(1L);
		assertNotEquals("Entries should not be equal if one has null and other doesn't for a field", entry11, entry13);
	}

	@Test
	public void testHashCode() {
		RouteRequestEntry entry1 = new RouteRequestEntry(testUuid);
		entry1.setDestinationNodeLocalId(1L);
		entry1.setPreviousHopLocalId(2L);

		// Consistency: hashCode multiple times returns same value
		int initialHashCode = entry1.hashCode();
		assertEquals("HashCode should be consistent", initialHashCode, entry1.hashCode());

		RouteRequestEntry entry2 = new RouteRequestEntry(testUuid);
		entry2.setDestinationNodeLocalId(1L);
		entry2.setPreviousHopLocalId(2L);

		// If equals is true, hashCodes must be same
		assertEquals("Equal objects must have equal hashCodes", entry1.hashCode(), entry2.hashCode());

		RouteRequestEntry entry3 = new RouteRequestEntry(UUID.randomUUID().toString()); // Different UUID
		assertNotEquals("HashCodes for unequal objects are likely different", entry1.hashCode(), entry3.hashCode());
	}

	@Test
	public void testToString() {
		String specificUuid = "test-uuid-123";
		Long destId = 777L;
		Long prevHopId = 888L;

		RouteRequestEntry entry = new RouteRequestEntry(specificUuid);
		entry.setDestinationNodeLocalId(destId);
		entry.setPreviousHopLocalId(prevHopId);

		String strRepresentation = entry.toString();
		assertNotNull("toString() should not return null", strRepresentation);
		assertTrue("toString() should contain requestUuid", strRepresentation.contains(specificUuid));
		assertTrue("toString() should contain destinationNodeLocalId", strRepresentation.contains(String.valueOf(destId)));
		assertTrue("toString() should contain previousHopLocalId", strRepresentation.contains(String.valueOf(prevHopId)));
	}
}
