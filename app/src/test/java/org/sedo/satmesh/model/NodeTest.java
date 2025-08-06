package org.sedo.satmesh.model;

import static org.junit.Assert.assertNotEquals;

import android.os.Build;
import android.os.Bundle;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.sedo.satmesh.proto.PersonalInfo;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = {Build.VERSION_CODES.UPSIDE_DOWN_CAKE}) // Target SDK 35 (Android 14)
public class NodeTest extends TestCase {

	private Node node;

	@Before
	public void setUp() throws Exception {
		super.setUp();
		node = new Node();
	}

	@Test
	public void testDefaultConstructor() {
		assertNull(node.getId());
		assertNull(node.getDisplayName());
		assertNull(node.getAddressName());
		assertFalse(node.isTrusted());
		assertNull(node.getLastSeen());
	}

	@Test
	public void testCopyConstructor() {
		Node original = new Node();
		original.setId(1L);
		original.setDisplayName("Test Name");
		original.setAddressName("test.address");
		original.setTrusted(true);
		original.setLastSeen(12345L);

		Node copy = new Node(original);

		assertNotSame("Copy should be a different instance", original, copy);
		assertEquals("ID should be copied", original.getId(), copy.getId());
		assertEquals("DisplayName should be copied", original.getDisplayName(), copy.getDisplayName());
		assertEquals("AddressName should be copied", original.getAddressName(), copy.getAddressName());
		assertEquals("Trusted status should be copied", original.isTrusted(), copy.isTrusted());
		assertEquals("LastSeen should be copied", original.getLastSeen(), copy.getLastSeen());
	}

	@Test
	public void testSetPersonalInfo() {
		PersonalInfo info = PersonalInfo.newBuilder()
				.setAddressName("test.address")
				.setDisplayName("Test Display")
				.build();
		node.setPersonalInfo(info);
		assertEquals("Test Display", node.getDisplayName());
		assertEquals("test.address", node.getAddressName());
	}

	@Test
	public void testToPersonalInfo() {
		node.setAddressName("to.proto.address");
		node.setDisplayName("To Proto Display");

		PersonalInfo infoTrue = node.toPersonalInfo(true);
		assertEquals("To Proto Display", infoTrue.getDisplayName());
		assertEquals("to.proto.address", infoTrue.getAddressName());
		assertTrue(infoTrue.getExpectResult());

		PersonalInfo infoFalse = node.toPersonalInfo(false);
		assertEquals("To Proto Display", infoFalse.getDisplayName());
		assertEquals("to.proto.address", infoFalse.getAddressName());
		assertFalse(infoFalse.getExpectResult());
	}

	@Test
	public void testGetNonNullName() {
		node.setDisplayName("Display Name");
		node.setAddressName("Address Name");
		assertEquals("Display Name", node.getNonNullName());

		node.setDisplayName(null);
		assertEquals("Address Name", node.getNonNullName());
	}

	@Test
	public void testEqualsAndHashCode() {
		Node node1 = new Node();
		node1.setDisplayName("Test");
		node1.setAddressName("address");
		node1.setTrusted(true);
		node1.setLastSeen(100L);

		Node node2 = new Node(); // Same relevant fields for equals
		node2.setDisplayName("Test");
		node2.setAddressName("address");
		node2.setTrusted(true);
		node2.setLastSeen(100L);

		Node node3 = new Node(); // Different display name
		node3.setDisplayName("Different");
		node3.setAddressName("address");
		node3.setTrusted(true);
		node3.setLastSeen(100L);

		Node node4 = new Node(); // Different lastSeen
		node4.setDisplayName("Test");
		node4.setAddressName("address");
		node4.setTrusted(true);
		node4.setLastSeen(200L);

		// Reflexivity, Symmetry, Transitivity, Consistency for equals
		assertEquals("Equals should be reflexive", node1, node1);
		assertEquals("Equals should be symmetric", node1, node2);
		assertEquals("Equals should be symmetric (reverse)", node2, node1);
		assertEquals("HashCode should be same for equal objects", node1.hashCode(), node2.hashCode());

		assertNotEquals("Should not be equal if a field differs (displayName)", node1, node3);
		assertNotEquals("Should not be equal if a field differs (lastSeen)", node1, node4);

		assertNotEquals("Should not be equal to null", null, node1);
		assertNotEquals("Should not be equal to different type", new Object(), node1);
	}

	// Test for Bundle operations (would need Robolectric)
	@Test
	public void testWriteAndRestoreFromBundle() {
		// This test requires Robolectric or to be an instrumented test
		// For demonstration, assuming Bundle works as expected here.
		Bundle bundle = new Bundle();
		String prefix = "node_";

		Node original = new Node();
		original.setId(123L);
		original.setDisplayName("Bundle User");
		original.setAddressName("bundle.user.address");
		original.setTrusted(true);
		original.setLastSeen(123456L); // lastSeen is not written to bundle

		original.write(bundle, prefix);
		Node restored = Node.restoreFromBundle(bundle, prefix);

		assertEquals("ID should be restored", original.getId(), restored.getId());
		assertEquals("DisplayName should be restored", original.getDisplayName(), restored.getDisplayName());
		assertEquals("AddressName should be restored", original.getAddressName(), restored.getAddressName());
		assertEquals("Trusted status should be restored", original.isTrusted(), restored.isTrusted());
		assertNull("LastSeen is not restored, should be null", restored.getLastSeen());
	}
}