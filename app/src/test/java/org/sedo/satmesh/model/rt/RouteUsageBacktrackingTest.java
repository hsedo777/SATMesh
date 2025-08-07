package org.sedo.satmesh.model.rt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

public class RouteUsageBacktrackingTest {

	@Test
	public void constructorAndGetters() {
		String testUsageUuid = UUID.randomUUID().toString();
		Long testDestinationNodeLocalId = 123L;

		RouteUsageBacktracking rub = new RouteUsageBacktracking(testUsageUuid, testDestinationNodeLocalId);
		assertEquals("Constructor should set usageUuid correctly", testUsageUuid, rub.usageUuid());
		assertEquals("Constructor should set destinationNodeLocalId correctly", testDestinationNodeLocalId, rub.destinationNodeLocalId());
	}

	@Test
	public void testEquals() {
		String uuid1 = UUID.randomUUID().toString();
		Long id1 = 1L;
		RouteUsageBacktracking rub1 = new RouteUsageBacktracking(uuid1, id1);
		RouteUsageBacktracking rub1Again = new RouteUsageBacktracking(uuid1, id1);

		String uuid2 = UUID.randomUUID().toString();
		Long id2 = 2L;
		RouteUsageBacktracking rub2 = new RouteUsageBacktracking(uuid2, id1); // Different UUID, same ID
		RouteUsageBacktracking rub3 = new RouteUsageBacktracking(uuid1, id2); // Same UUID, different ID
		RouteUsageBacktracking rub4 = new RouteUsageBacktracking(uuid2, id2); // Different UUID, different ID

		// Reflexivity
		assertEquals("An object must be equal to itself.", rub1, rub1);

		// Symmetry
		assertEquals("If A equals B, then B must equal A.", rub1, rub1Again);
		assertEquals("Symmetry check failed.", rub1Again, rub1);

		// Consistency
		assertEquals("Multiple calls to equals with the same objects should return the same result.", rub1, rub1Again);

		// Non-nullity
		assertNotEquals("An object should not be equal to null.", null, rub1);

		// Different types
		assertNotEquals("An object should not be equal to an object of a different type.", new Object(), rub1);

		// Different usageUuid
		assertNotEquals("Objects with different usageUuids should not be equal.", rub1, rub2);

		// Different destinationNodeLocalId
		assertNotEquals("Objects with different destinationNodeLocalIds should not be equal.", rub1, rub3);

		// Both different
		assertNotEquals("Objects with different usageUuids and destinationNodeLocalIds should not be equal.", rub1, rub4);
	}

	@Test
	public void testHashCode() {
		String uuid1 = UUID.randomUUID().toString();
		Long id1 = 1L;
		RouteUsageBacktracking rub1 = new RouteUsageBacktracking(uuid1, id1);
		RouteUsageBacktracking rub1Again = new RouteUsageBacktracking(uuid1, id1); // Same as rub1

		// Consistency: hashCode must consistently return the same integer
		int initialHashCode = rub1.hashCode();
		assertEquals("hashCode should be consistent across multiple calls.", initialHashCode, rub1.hashCode());
		assertEquals("hashCode should be consistent across multiple calls.", initialHashCode, rub1.hashCode());

		// Equality: if two objects are equal according to equals(), they must have the same hashCode
		assertEquals("Equal objects must have equal hashCodes.", rub1.hashCode(), rub1Again.hashCode());

		String uuid2 = UUID.randomUUID().toString();
		RouteUsageBacktracking rub2 = new RouteUsageBacktracking(uuid2, id1); // Different UUID
		if (rub1.equals(rub2)) { // Should be false
			assertEquals("If objects are equal, hashCodes must be equal.", rub1.hashCode(), rub2.hashCode());
		}
	}

	@Test
	public void testToString() {
		String testUsageUuid = UUID.randomUUID().toString();
		Long testDestinationNodeLocalId = 456L;
		RouteUsageBacktracking rub = new RouteUsageBacktracking(testUsageUuid, testDestinationNodeLocalId);

		String str = rub.toString();
		assertTrue("toString() should contain usageUuid.", str.contains(testUsageUuid));
		assertTrue("toString() should contain destinationNodeLocalId.", str.contains(testDestinationNodeLocalId.toString()));
		assertTrue("toString() should generally follow record format.", str.startsWith("RouteUsageBacktracking[") && str.endsWith("]"));
	}
}
