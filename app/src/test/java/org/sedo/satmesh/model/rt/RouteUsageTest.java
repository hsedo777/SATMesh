package org.sedo.satmesh.model.rt;

import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class RouteUsageTest {

    @Test
    public void constructor() {
        String testUuid = UUID.randomUUID().toString();
        RouteUsage routeUsage = new RouteUsage(testUuid);

        assertEquals("UsageRequestUuid should match constructor argument", testUuid, routeUsage.getUsageRequestUuid());
        assertNull("RouteEntryDiscoveryUuid should be null initially", routeUsage.getRouteEntryDiscoveryUuid());
        assertNull("PreviousHopLocalId should be null initially", routeUsage.getPreviousHopLocalId());
    }

    @Test
    public void getUsageRequestUuid() {
        String testUuid = UUID.randomUUID().toString();
        RouteUsage routeUsage = new RouteUsage(testUuid);
        assertEquals(testUuid, routeUsage.getUsageRequestUuid());
    }

    @Test
    public void setAndGetRouteEntryDiscoveryUuid() {
        RouteUsage routeUsage = new RouteUsage(UUID.randomUUID().toString());
        String discoveryUuid = UUID.randomUUID().toString();
        routeUsage.setRouteEntryDiscoveryUuid(discoveryUuid);
        assertEquals("Getter should return the set RouteEntryDiscoveryUuid", discoveryUuid, routeUsage.getRouteEntryDiscoveryUuid());

        routeUsage.setRouteEntryDiscoveryUuid(null);
        assertNull("Getter should return null if RouteEntryDiscoveryUuid is set to null", routeUsage.getRouteEntryDiscoveryUuid());
    }

    @Test
    public void setAndGetPreviousHopLocalId() {
        RouteUsage routeUsage = new RouteUsage(UUID.randomUUID().toString());
        Long hopId = 12345L;
        routeUsage.setPreviousHopLocalId(hopId);
        assertEquals("Getter should return the set PreviousHopLocalId", hopId, routeUsage.getPreviousHopLocalId());

        routeUsage.setPreviousHopLocalId(null);
        assertNull("Getter should return null if PreviousHopLocalId is set to null", routeUsage.getPreviousHopLocalId());
    }

    @Test
    public void testEquals() {
        String uuid1 = UUID.randomUUID().toString();
        String uuid2 = UUID.randomUUID().toString();
        String discoveryUuid1 = UUID.randomUUID().toString();
        String discoveryUuid2 = UUID.randomUUID().toString();
        Long hopId1 = 1L;
        Long hopId2 = 2L;

        RouteUsage ru1 = new RouteUsage(uuid1);
        ru1.setRouteEntryDiscoveryUuid(discoveryUuid1);
        ru1.setPreviousHopLocalId(hopId1);

        RouteUsage ru2_sameAsRu1 = new RouteUsage(uuid1); // Same usageRequestUuid
        ru2_sameAsRu1.setRouteEntryDiscoveryUuid(discoveryUuid1);
        ru2_sameAsRu1.setPreviousHopLocalId(hopId1);

        RouteUsage ru3_diffUsageUuid = new RouteUsage(uuid2); // Different usageRequestUuid
        ru3_diffUsageUuid.setRouteEntryDiscoveryUuid(discoveryUuid1);
        ru3_diffUsageUuid.setPreviousHopLocalId(hopId1);

        RouteUsage ru4_diffDiscoveryUuid = new RouteUsage(uuid1);
        ru4_diffDiscoveryUuid.setRouteEntryDiscoveryUuid(discoveryUuid2); // Different discoveryUuid
        ru4_diffDiscoveryUuid.setPreviousHopLocalId(hopId1);

        RouteUsage ru5_diffHopId = new RouteUsage(uuid1);
        ru5_diffHopId.setRouteEntryDiscoveryUuid(discoveryUuid1);
        ru5_diffHopId.setPreviousHopLocalId(hopId2); // Different hopId

        RouteUsage ru6_nullDiscovery = new RouteUsage(uuid1);
        ru6_nullDiscovery.setPreviousHopLocalId(hopId1);
        // routeEntryDiscoveryUuid is null

        RouteUsage ru7_nullHopId = new RouteUsage(uuid1);
        ru7_nullHopId.setRouteEntryDiscoveryUuid(discoveryUuid1);
        // previousHopLocalId is null

        RouteUsage ru8_allNullOptional = new RouteUsage(uuid1);
        // both optional fields null

        // Reflexivity
        assertEquals("An object must be equal to itself.", ru1, ru1);

        // Symmetry
        assertEquals("If ru1 is equal to ru2_sameAsRu1, then ru2_sameAsRu1 must be equal to ru1.", ru1, ru2_sameAsRu1);
        assertEquals("Symmetry check failed.", ru2_sameAsRu1, ru1);

        // Non-nullity
        assertNotEquals("An object must not be equal to null.", null, ru1);

        // Different types
        assertNotEquals("An object must not be equal to an object of a different type.",  new Object(), ru1);

        // Test inequality with different usageRequestUuid
        assertNotEquals("Objects with different usageRequestUuid should not be equal.", ru1, ru3_diffUsageUuid);

        // Test inequality with different routeEntryDiscoveryUuid
        assertNotEquals("Objects with different routeEntryDiscoveryUuid should not be equal.", ru1, ru4_diffDiscoveryUuid);
        assertNotEquals("Objects with different routeEntryDiscoveryUuid (one null) should not be equal.", ru1, ru6_nullDiscovery);
        assertNotEquals("Objects with different routeEntryDiscoveryUuid (one null) should not be equal.", ru6_nullDiscovery, ru1);


        // Test inequality with different previousHopLocalId
        assertNotEquals("Objects with different previousHopLocalId should not be equal.", ru1, ru5_diffHopId);
        assertNotEquals("Objects with different previousHopLocalId (one null) should not be equal.", ru1, ru7_nullHopId);
        assertNotEquals("Objects with different previousHopLocalId (one null) should not be equal.", ru7_nullHopId, ru1);

        // Test equality with null fields
        RouteUsage ru6_copy = new RouteUsage(uuid1);
        ru6_copy.setPreviousHopLocalId(hopId1);
        assertEquals("Objects with same null routeEntryDiscoveryUuid should be equal.", ru6_nullDiscovery, ru6_copy);

        RouteUsage ru7_copy = new RouteUsage(uuid1);
        ru7_copy.setRouteEntryDiscoveryUuid(discoveryUuid1);
        assertEquals("Objects with same null previousHopLocalId should be equal.", ru7_nullHopId, ru7_copy);
        
        RouteUsage ru8_copy = new RouteUsage(uuid1);
        assertEquals("Objects with both optional fields null should be equal.", ru8_allNullOptional, ru8_copy);
    }

    @Test
    public void testHashCode() {
        String uuid1 = UUID.randomUUID().toString();
        String discoveryUuid1 = UUID.randomUUID().toString();
        Long hopId1 = 1L;

        RouteUsage ru1 = new RouteUsage(uuid1);
        ru1.setRouteEntryDiscoveryUuid(discoveryUuid1);
        ru1.setPreviousHopLocalId(hopId1);

        RouteUsage ru2_sameAsRu1 = new RouteUsage(uuid1);
        ru2_sameAsRu1.setRouteEntryDiscoveryUuid(discoveryUuid1);
        ru2_sameAsRu1.setPreviousHopLocalId(hopId1);

        // Consistency: hashCode must consistently return the same integer
        int initialHashCode = ru1.hashCode();
        assertEquals("hashCode should be consistent.", initialHashCode, ru1.hashCode());
        assertEquals("hashCode should be consistent even after field modification and restoration.", initialHashCode, ru1.hashCode());


        // Equality: If two objects are equal according to the equals(Object) method,
        // then calling the hashCode method on each of the two objects must produce the same integer result.
        assertEquals("Equal objects must have equal hashCodes.", ru1.hashCode(), ru2_sameAsRu1.hashCode());

        // Test with null fields
        RouteUsage ru_nullDiscovery = new RouteUsage(uuid1);
        ru_nullDiscovery.setPreviousHopLocalId(hopId1);
        RouteUsage ru_nullDiscovery_copy = new RouteUsage(uuid1);
        ru_nullDiscovery_copy.setPreviousHopLocalId(hopId1);
        assertEquals("Equal objects (with null discovery UUID) must have equal hashCodes.", ru_nullDiscovery.hashCode(), ru_nullDiscovery_copy.hashCode());

        RouteUsage ru_nullHop = new RouteUsage(uuid1);
        ru_nullHop.setRouteEntryDiscoveryUuid(discoveryUuid1);
        RouteUsage ru_nullHop_copy = new RouteUsage(uuid1);
        ru_nullHop_copy.setRouteEntryDiscoveryUuid(discoveryUuid1);
        assertEquals("Equal objects (with null hop ID) must have equal hashCodes.", ru_nullHop.hashCode(), ru_nullHop_copy.hashCode());
        
        RouteUsage ru_bothNull = new RouteUsage(uuid1);
        RouteUsage ru_bothNull_copy = new RouteUsage(uuid1);
        assertEquals("Equal objects (with both optionals null) must have equal hashCodes.", ru_bothNull.hashCode(), ru_bothNull_copy.hashCode());
    }

    @Test
    public void testToString() {
        String usageUuid = "usage-" + UUID.randomUUID().toString();
        String discoveryUuid = "discovery-" + UUID.randomUUID().toString();
        Long hopId = 987L;

        RouteUsage routeUsage = new RouteUsage(usageUuid);
        routeUsage.setRouteEntryDiscoveryUuid(discoveryUuid);
        routeUsage.setPreviousHopLocalId(hopId);

        String str = routeUsage.toString();

        assertTrue("toString should contain usageRequestUuid.", str.contains(usageUuid));
        assertTrue("toString should contain routeEntryDiscoveryUuid.", str.contains(discoveryUuid));
        assertTrue("toString should contain previousHopLocalId.", str.contains(String.valueOf(hopId)));

        RouteUsage routeUsageNulls = new RouteUsage(usageUuid);
        String strNulls = routeUsageNulls.toString();
        assertTrue("toString should contain usageRequestUuid even with nulls.", strNulls.contains(usageUuid));
        assertTrue("toString should indicate null for routeEntryDiscoveryUuid.", strNulls.contains("routeEntryDiscoveryUuid='null'"));
        assertTrue("toString should indicate null for previousHopLocalId.", strNulls.contains("previousHopLocalId=null"));
    }
}
