package org.sedo.satmesh.utils;

public class Constants {

	// SharedPreferences Keys
	public static final String PREFS_FILE_NAME = "SatMeshPrefs";
	// Keys for host node
	public static final String PREF_KEY_IS_SETUP_COMPLETE = "is_setup_complete";
	public static final String PREF_KEY_HOST_NODE_ID = "host_node_id";
	public static final String NODE_ADDRESS_NAME_PREFIX = "satmesh-";
	public static final String PREF_KEY_HOST_ADDRESS_NAME = "host_address_name";

	// Fragment Tags
	public static final String TAG_WELCOME_FRAGMENT = "WelcomeFragment";
	public static final String TAG_CHAT_LIST_FRAGMENT = "ChatListFragment";
	public static final String TAG_CHAT_FRAGMENT = "ChatFragment";
	public static final String TAG_DISCOVERY_FRAGMENT = "NearbyDiscoveryFragment";
	public static final String TAG_SEARCH_FRAGMENT = "SearchFragment";

	public static final int SIGNAL_PROTOCOL_DEVICE_ID = 1;

	public static final String CHANNEL_ID_MESSAGES = "satmesh_messages_channel";
	public static final String CHANNEL_ID_NETWORK_EVENTS = "satmesh_network_events_channel";

	public static final int NOTIFICATION_ID_NEW_MESSAGE = 1000;
	public static final int NOTIFICATION_ID_NEW_NODE_DISCOVERED = 1001;
	public static final int NOTIFICATION_ID_ROUTE_DISCOVERY_INITIATED = 1002;
	public static final int NOTIFICATION_ID_ROUTE_DISCOVERY_RESULT = 1003;

	private Constants() {}
}