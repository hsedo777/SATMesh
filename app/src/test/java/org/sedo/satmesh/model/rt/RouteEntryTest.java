package org.sedo.satmesh.model.rt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

public class RouteEntryTest {

	@Test
	public void defaultConstructor() {
		RouteEntry entry = new RouteEntry();
		assertNull("ID should be null after default construction", entry.getId());
		assertNull("DiscoveryUuid should be null", entry.getDiscoveryUuid());
		assertNull("DestinationNodeLocalId should be null", entry.getDestinationNodeLocalId());
		assertNull("NextHopLocalId should be null", entry.getNextHopLocalId());
		assertNull("PreviousHopLocalId should be null", entry.getPreviousHopLocalId());
		assertNull("HopCount should be null", entry.getHopCount());
		assertNull("LastUseTimestamp should be null", entry.getLastUseTimestamp());
	}

	@Test
	public void setAndGetId() {
		RouteEntry entry = new RouteEntry();
		Long testId = 1L;
		entry.setId(testId);
		assertEquals("Getter for ID should return the set value", testId, entry.getId());
	}

	@Test
	public void setAndGetDiscoveryUuid() {
		RouteEntry entry = new RouteEntry();
		String testUuid = UUID.randomUUID().toString();
		entry.setDiscoveryUuid(testUuid);
		assertEquals("Getter for DiscoveryUuid should return the set value", testUuid, entry.getDiscoveryUuid());
	}

	@Test
	public void setAndGetDestinationNodeLocalId() {
		RouteEntry entry = new RouteEntry();
		Long testId = 100L;
		entry.setDestinationNodeLocalId(testId);
		assertEquals("Getter for DestinationNodeLocalId should return the set value", testId, entry.getDestinationNodeLocalId());
	}

	@Test
	public void setAndGetNextHopLocalId() {
		RouteEntry entry = new RouteEntry();
		Long testId = 200L;
		entry.setNextHopLocalId(testId);
		assertEquals("Getter for NextHopLocalId should return the set value", testId, entry.getNextHopLocalId());
	}

	@Test
	public void setAndGetPreviousHopLocalId() {
		RouteEntry entry = new RouteEntry();
		Long testId = 300L;
		entry.setPreviousHopLocalId(testId);
		assertEquals("Getter for PreviousHopLocalId should return the set value", testId, entry.getPreviousHopLocalId());
	}

	@Test
	public void setAndGetHopCount() {
		RouteEntry entry = new RouteEntry();
		Integer testHopCount = 5;
		entry.setHopCount(testHopCount);
		assertEquals("Getter for HopCount should return the set value", testHopCount, entry.getHopCount());
	}

	@Test
	public void setAndGetLastUseTimestamp() {
		RouteEntry entry = new RouteEntry();
		Long testTimestamp = System.currentTimeMillis();
		entry.setLastUseTimestamp(testTimestamp);
		assertEquals("Getter for LastUseTimestamp should return the set value", testTimestamp, entry.getLastUseTimestamp());
	}

	@Test
	public void testEquals() {
		RouteEntry entry1 = new RouteEntry();
		String discoveryUuid = UUID.randomUUID().toString();
		Long destId = 1L;
		Long nextHopId = 2L;
		Long prevHopId = 3L;
		Integer hopCount = 4;
		Long lastUse = System.currentTimeMillis();

		entry1.setDiscoveryUuid(discoveryUuid);
		entry1.setDestinationNodeLocalId(destId);
		entry1.setNextHopLocalId(nextHopId);
		entry1.setPreviousHopLocalId(prevHopId);
		entry1.setHopCount(hopCount);
		entry1.setLastUseTimestamp(lastUse);

		// Reflexivity
		assertEquals("An object must be equal to itself.", entry1, entry1);

		RouteEntry entry2 = new RouteEntry();
		entry2.setDiscoveryUuid(discoveryUuid);
		entry2.setDestinationNodeLocalId(destId);
		entry2.setNextHopLocalId(nextHopId);
		entry2.setPreviousHopLocalId(prevHopId);
		entry2.setHopCount(hopCount);
		entry2.setLastUseTimestamp(lastUse);

		// Symmetry
		assertEquals("If A is equal to B, then B must be equal to A.", entry1, entry2);
		assertEquals("Symmetry check failed.", entry2, entry1);

		RouteEntry entry3 = new RouteEntry(); // Different values
		entry3.setDiscoveryUuid(UUID.randomUUID().toString());
		entry3.setDestinationNodeLocalId(10L);
		entry3.setNextHopLocalId(20L);
		entry3.setPreviousHopLocalId(30L);
		entry3.setHopCount(5);
		entry3.setLastUseTimestamp(System.currentTimeMillis() + 1000);

		// Different values
		assertNotEquals(entry1, entry3);

		// Consistency: Multiple calls to equals with the same objects should yield the same result.
		assertEquals("Consistency check failed.", entry1, entry2);

		// Non-nullity
		assertNotEquals("An object must not be equal to null.", null, entry1);

		// Different type
		assertNotEquals("An object must not be equal to an object of a different type.", new Object(), entry1);

		// ID is not part of equals, so let's set different IDs
		entry1.setId(100L);
		entry2.setId(200L);
		assertEquals("Entries with same relevant fields but different IDs should still be equal.", entry1, entry2);

		// Test inequality with different discoveryUuid
		RouteEntry temp = createFullRouteEntry();
		temp.setDiscoveryUuid(UUID.randomUUID().toString());
		assertNotEquals("Entries with different discoveryUuid should not be equal.", entry1, temp);

		// Test inequality with different destinationNodeLocalId
		temp = createFullRouteEntry();
		temp.setDestinationNodeLocalId(destId + 1L);
		assertNotEquals("Entries with different destinationNodeLocalId should not be equal.", entry1, temp);

		// Test inequality with different nextHopLocalId
		temp = createFullRouteEntry();
		temp.setNextHopLocalId(nextHopId + 1L);
		assertNotEquals("Entries with different nextHopLocalId should not be equal.", entry1, temp);

		// Test inequality with different previousHopLocalId
		temp = createFullRouteEntry();
		temp.setPreviousHopLocalId(prevHopId + 1L);
		assertNotEquals("Entries with different previousHopLocalId should not be equal.", entry1, temp);

		// Test inequality with different hopCount
		temp = createFullRouteEntry();
		temp.setHopCount(hopCount + 1);
		assertNotEquals("Entries with different hopCount should not be equal.", entry1, temp);

		// Test inequality with different lastUseTimestamp
		temp = createFullRouteEntry();
		temp.setLastUseTimestamp(lastUse + 1L);
		assertNotEquals("Entries with different lastUseTimestamp should not be equal.", entry1, temp);

		// Test with some null fields
		RouteEntry entryWithNulls1 = new RouteEntry();
		RouteEntry entryWithNulls2 = new RouteEntry();
		assertEquals("Two new entries with null fields should be equal.", entryWithNulls1, entryWithNulls2);
		entryWithNulls1.setDiscoveryUuid(discoveryUuid);
		assertNotEquals("Should not be equal if one has a null field and the other doesn't.", entryWithNulls1, entryWithNulls2);
		entryWithNulls2.setDiscoveryUuid(discoveryUuid);
		assertEquals("Should be equal if both have same non-null field and others null.", entryWithNulls1, entryWithNulls2);
	}

	private RouteEntry createFullRouteEntry() {
		RouteEntry entry = new RouteEntry();
		entry.setDiscoveryUuid(UUID.randomUUID().toString());
		entry.setDestinationNodeLocalId(1L);
		entry.setNextHopLocalId(2L);
		entry.setPreviousHopLocalId(3L);
		entry.setHopCount(4);
		entry.setLastUseTimestamp(System.currentTimeMillis());
		return entry;
	}

	@Test
	public void testHashCode() {
		RouteEntry entry1 = new RouteEntry();
		String discoveryUuid = UUID.randomUUID().toString();
		Long destId = 1L;
		Long nextHopId = 2L;
		Long prevHopId = 3L;
		Integer hopCount = 4;
		Long lastUse = System.currentTimeMillis();

		entry1.setDiscoveryUuid(discoveryUuid);
		entry1.setDestinationNodeLocalId(destId);
		entry1.setNextHopLocalId(nextHopId);
		entry1.setPreviousHopLocalId(prevHopId);
		entry1.setHopCount(hopCount);
		entry1.setLastUseTimestamp(lastUse);

		RouteEntry entry2 = new RouteEntry();
		entry2.setDiscoveryUuid(discoveryUuid);
		entry2.setDestinationNodeLocalId(destId);
		entry2.setNextHopLocalId(nextHopId);
		entry2.setPreviousHopLocalId(prevHopId);
		entry2.setHopCount(hopCount);
		entry2.setLastUseTimestamp(lastUse);

		// Consistency: hashCode should return the same value on multiple calls if the object's state hasn't changed.
		int initialHashCode = entry1.hashCode();
		assertEquals("hashCode should be consistent.", initialHashCode, entry1.hashCode());

		// Equality: If two objects are equal according to equals(), their hashCode() must be the same.
		assertEquals("Equal objects must have equal hashCodes.", entry1.hashCode(), entry2.hashCode());

		// ID is not part of hashCode, so let's set different IDs
		entry1.setId(100L);
		entry2.setId(200L);
		assertEquals("Equal objects (ignoring ID) must have equal hashCodes.", entry1.hashCode(), entry2.hashCode());

		// Test with nulls (default constructor)
		RouteEntry entryNull1 = new RouteEntry();
		RouteEntry entryNull2 = new RouteEntry();
		assertEquals("Hashcode for two new entries with null fields should be equal.", entryNull1.hashCode(), entryNull2.hashCode());
	}

	@Test
	public void testToString() {
		RouteEntry entry = new RouteEntry();
		entry.setId(123L);
		String discoveryUuid = UUID.randomUUID().toString();
		entry.setDiscoveryUuid(discoveryUuid);
		entry.setDestinationNodeLocalId(1L);
		entry.setNextHopLocalId(2L);
		entry.setPreviousHopLocalId(3L);
		entry.setHopCount(5);
		entry.setLastUseTimestamp(System.currentTimeMillis());

		String str = entry.toString();

		assertTrue("toString should contain id", str.contains("id=123"));
		assertTrue("toString should contain discoveryUuid", str.contains("discoveryUuid='" + discoveryUuid + "'"));
		assertTrue("toString should contain destinationNodeLocalId", str.contains("destinationNodeLocalId=1"));
		assertTrue("toString should contain nextHopLocalId", str.contains("nextHopLocalId=2"));
		assertTrue("toString should contain previousHopLocalId", str.contains("previousHopLocalId=3"));
		assertTrue("toString should contain hopCount", str.contains("hopCount=5"));
		assertTrue("toString should contain lastUseTimestamp", str.contains("lastUseTimestamp=" + entry.getLastUseTimestamp()));
	}
}
