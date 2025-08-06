package org.sedo.satmesh.model.rt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

public class BroadcastStatusEntryTest {

	private String testRequestUuid;
	private long testNeighborNodeLocalId;
	private BroadcastStatusEntry entry1;
	private BroadcastStatusEntry entry2;

	@Before
	public void setUp() {
		testRequestUuid = UUID.randomUUID().toString();
		testNeighborNodeLocalId = 12345L;
		entry1 = new BroadcastStatusEntry(testRequestUuid, testNeighborNodeLocalId);
		// entry2 will be used for equality tests, initialized within test methods
	}

	@Test
	public void constructor() {
		assertEquals("Request UUID should match constructor argument", testRequestUuid, entry1.getRequestUuid());
		assertEquals("Neighbor node local ID should match constructor argument", testNeighborNodeLocalId, entry1.getNeighborNodeLocalId());
		assertFalse("isPendingResponseInProgress should default to false", entry1.isPendingResponseInProgress());
	}

	@Test
	public void isPendingResponseInProgress() {
		assertFalse("Initial state should be false", entry1.isPendingResponseInProgress());
		entry1.setPendingResponseInProgress(true);
		assertTrue("State should be true after setting to true", entry1.isPendingResponseInProgress());
		entry1.setPendingResponseInProgress(false);
		assertFalse("State should be false after setting to false", entry1.isPendingResponseInProgress());
	}

	@Test
	public void testEquals() {
		// Reflexivity
		assertEquals("An object must be equal to itself.", entry1, entry1);

		// Null check
		assertNotEquals("An object must not be equal to null.", null, entry1);

		// Different type check
		assertNotEquals("An object must not be equal to an object of a different type.", new Object(), entry1);

		// Symmetry
		entry2 = new BroadcastStatusEntry(testRequestUuid, testNeighborNodeLocalId);
		assertEquals("Symmetric check: entry1 should be equal to entry2.", entry1, entry2);
		assertEquals("Symmetric check: entry2 should be equal to entry1.", entry2, entry1);

		entry2 = new BroadcastStatusEntry(testRequestUuid, testNeighborNodeLocalId);
		entry1.setPendingResponseInProgress(true);
		entry2.setPendingResponseInProgress(true);
		assertEquals(entry1, entry2);

		entry1.setPendingResponseInProgress(false);
		entry2.setPendingResponseInProgress(false);
		assertEquals(entry1, entry2);

		entry2 = new BroadcastStatusEntry(UUID.randomUUID().toString(), testNeighborNodeLocalId);
		assertNotEquals(entry1, entry2);

		entry2 = new BroadcastStatusEntry(testRequestUuid, 67890L);
		assertNotEquals(entry1, entry2);

		entry2 = new BroadcastStatusEntry(testRequestUuid, testNeighborNodeLocalId);
		entry1.setPendingResponseInProgress(true);
		// entry2.isPendingResponseInProgress is false by default
		assertNotEquals(entry1, entry2);
	}

	@Test
	public void testHashCode() {
		entry2 = new BroadcastStatusEntry(testRequestUuid, testNeighborNodeLocalId);
		assertEquals("Hashcodes should be equal for equal objects.", entry1.hashCode(), entry2.hashCode());

		entry1.setPendingResponseInProgress(true);
		entry2.setPendingResponseInProgress(true);
		assertEquals("Hashcodes should be equal for equal objects after modification.", entry1.hashCode(), entry2.hashCode());

		BroadcastStatusEntry entry3 = new BroadcastStatusEntry(testRequestUuid, 67890L);
		assertNotEquals("Hashcodes should likely be different for non-equal objects.", entry1.hashCode(), entry3.hashCode());
	}

	@Test
	public void testToString() {
		String str = entry1.toString();
		assertNotNull(str);
		assertTrue("toString should contain requestUuid", str.contains("requestUuid='" + testRequestUuid + "'"));
		assertTrue("toString should contain neighborNodeLocalId", str.contains("neighborNodeLocalId=" + testNeighborNodeLocalId));
		assertTrue("toString should contain isPendingResponseInProgress", str.contains("isPendingResponseInProgress=false"));

		entry1.setPendingResponseInProgress(true);
		assertTrue("toString should reflect updated isPendingResponseInProgress", entry1.toString().contains("isPendingResponseInProgress=true"));
	}
}
