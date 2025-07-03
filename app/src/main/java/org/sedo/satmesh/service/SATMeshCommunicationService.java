package org.sedo.satmesh.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.MainActivity;
import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.nearby.NearbySignalMessenger;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.utils.Constants;
import org.sedo.satmesh.utils.NotificationType;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class SATMeshCommunicationService extends Service {

	// Intent Actions for the service
	public static final String ACTION_START_FOREGROUND_SERVICE = "org.sedo.satmesh.action.START_FOREGROUND_SERVICE";
	public static final String ACTION_STOP_FOREGROUND_SERVICE = "org.sedo.satmesh.action.STOP_FOREGROUND_SERVICE";
	public static final String ACTION_INITIALIZE_COMMUNICATION_MODULES = "org.sedo.satmesh.action.INITIALIZE_COMMUNICATION_MODULES";
	public static final String ACTION_STOP_COMMUNICATION_MODULES = "org.sedo.satmesh.action.STOP_COMMUNICATION_MODULES";
	private static final String TAG = "SATMeshCommService";
	private static final String NOTIFICATION_CHANNEL_ID = "SATMesh_Communication_Channel";
	private static final int NOTIFICATION_ID = 1; // Unique ID for the foreground service notification
	private final NotificationIdProvider idProvider;
	// Instances of communication managers
	private NearbyManager nearbyManager;
	private SignalManager signalManager;
	private NearbySignalMessenger nearbySignalMessenger;
	// Database and SharedPreferences
	private AppDatabase appDatabase;
	private SharedPreferences sharedPreferences;
	private ExecutorService serviceExecutor; // Executor for internal service operations
	// Host node state and communication modules state
	private Node hostNode;

	public SATMeshCommunicationService() {
		idProvider = new NotificationIdProvider();
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "Service onCreate");

		// Create the notification channel for Android Oreo and above
		createNotificationChannels();

		// Initialize the Executor for service operations
		serviceExecutor = Executors.newSingleThreadExecutor();

		// Initialize database and SharedPreferences
		appDatabase = AppDatabase.getDB(getApplicationContext());
		sharedPreferences = getSharedPreferences(Constants.PREFS_FILE_NAME, MODE_PRIVATE);

		/*
		 * On service start, check if the host node is already configured.
		 * If yes, initialize communication modules immediately.
		 * Otherwise, it will wait for an explicit Intent from MainActivity.
		 */
		checkAndInitializeCommunicationModules();
	}

	private synchronized boolean areCommunicationModulesInitialized() {
		return signalManager != null && nearbyManager != null && nearbySignalMessenger != null
				&& nearbyManager.isDiscovering() && nearbyManager.isAdvertising();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "Service onStartCommand received: " + (intent != null ? intent.getAction() : "null"));

		if (intent != null) {
			String action = intent.getAction();

			if (action != null) {
				switch (action) {
					case ACTION_START_FOREGROUND_SERVICE:
						// Start the foreground service
						startForeground(NOTIFICATION_ID, buildNotification());
						// Check and initialize if not already done
						checkAndInitializeCommunicationModules();
						break;
					case ACTION_INITIALIZE_COMMUNICATION_MODULES:
						// Explicit request to initialize communication modules
						checkAndInitializeCommunicationModules();
						break;
					case ACTION_STOP_COMMUNICATION_MODULES:
						// Explicit request to stop communication modules (e.g., when Nearby is no longer needed)
						stopCommunicationModules();
						break;
					case ACTION_STOP_FOREGROUND_SERVICE:
						// Stop the foreground service and the service itself
						stopForeground(STOP_FOREGROUND_REMOVE);
						stopSelf();
						break;
					case Constants.ACTION_SHOW_SATMESH_NOTIFICATION:
						startForeground(NOTIFICATION_ID, buildNotification());
						String notificationTypeString = intent.getStringExtra(Constants.EXTRA_NOTIFICATION_TYPE);
						Bundle notificationData = intent.getBundleExtra(Constants.EXTRA_NOTIFICATION_DATA_BUNDLE);
						if (notificationTypeString != null && notificationData != null) {
							try {
								NotificationType notificationType = NotificationType.valueOf(notificationTypeString);
								// Dispatch to the unified notification methods
								switch (notificationType) {
									case NEW_MESSAGE:
										showNewMessageNotification(notificationData);
										break;
									case NEW_NODE_DISCOVERED:
										showNewNodeDiscoveredNotification(notificationData);
										break;
									case ROUTE_DISCOVERY_INITIATED:
										showRouteDiscoveryInitiatedNotification(notificationData);
										break;
									case ROUTE_DISCOVERY_RESULT:
										showRouteDiscoveryResultNotification(notificationData);
										break;
									default:
										Log.w(TAG, "Unhandled NotificationType: " + notificationType);
										break;
								}
							} catch (IllegalArgumentException e) {
								Log.e(TAG, "Unknown NotificationType received: " + notificationTypeString, e);
							}
						} else {
							Log.w(TAG, "Notification Intent missing type or data bundle.");
						}
						break;
					case Constants.ACTION_NOTIFICATION_DISMISSED:
						if (intent.hasExtra(Constants.NOTIFICATION_ID) && intent.hasExtra(Constants.NOTIFICATION_GROUP_ID)
								&& intent.hasExtra(Constants.NOTIFICATION_GROUP_KEY)) {
							int notificationId = intent.getIntExtra(Constants.NOTIFICATION_ID, -1);
							int groupId = intent.getIntExtra(Constants.NOTIFICATION_GROUP_ID, -1);
							String groupKey = Objects.requireNonNull(intent.getStringExtra(Constants.NOTIFICATION_GROUP_KEY));
							if (groupId == notificationId) {
								// The group notification is already dismissed
								idProvider.removeGroup(groupKey, getApplicationContext());
							} else {
								// Dismiss on child element
								idProvider.decreaseGroupChildrenCount(groupKey, getApplicationContext());
							}
						}
						break;
					default:
						// Handle unknown actions
						Log.w(TAG, "Unknown action received: " + action);
						break;
				}
			}
		} else {
			// If the Intent is null, it means the service was restarted by the system
			// after being killed. Here, we want it to remain in the foreground.
			Log.d(TAG, "Service restarted by system, ensuring foreground status.");
			startForeground(NOTIFICATION_ID, buildNotification());
			checkAndInitializeCommunicationModules(); // Re-initialize in case of system restart
		}

		// START_STICKY means that if the service is killed by the system, it will be restarted.
		// The Intent will be null on automatic restart.
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "Service onDestroy");
		// Properly stop communication managers and shutdown the executor
		stopCommunicationModules();
		if (serviceExecutor != null) {
			serviceExecutor.shutdownNow(); // Immediately shuts down all running tasks
		}
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		// For now, we won't need to bind components to this service.
		return null;
	}

	/**
	 * Creates and displays the persistent notification for the foreground service.
	 *
	 * @return The Notification object to display.
	 */
	private Notification buildNotification() {
		// Create a PendingIntent for the notification click action
		// This will bring MainActivity to the foreground if the user clicks the notification
		Intent notificationIntent = new Intent(this, MainActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(
				this,
				0,
				notificationIntent,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
		);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
				.setContentTitle(getString(R.string.notification_title))
				.setContentText(getString(R.string.notification_content))
				.setSmallIcon(R.drawable.ic_notification)
				.setContentIntent(pendingIntent)
				.setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for a background notification
				.setOngoing(true); // Makes the notification non-dismissible by user

		return builder.build();
	}

	/**
	 * Creates the notification channel for Android 8.0 (API 26) and higher.
	 */
	private void createNotificationChannels() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = getString(R.string.channel_name);
			String description = getString(R.string.channel_description);
			int importance = NotificationManager.IMPORTANCE_LOW; // Low importance for a background notification

			NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
			channel.setDescription(description);

			// Messages channel
			NotificationChannel messagesChannel = new NotificationChannel(
					Constants.CHANNEL_ID_MESSAGES,
					getString(R.string.channel_name_messages),
					NotificationManager.IMPORTANCE_HIGH
			);
			messagesChannel.setDescription(getString(R.string.channel_description_messages));
			messagesChannel.enableVibration(true);
			messagesChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
			messagesChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null);

			// Network events channel
			NotificationChannel networkEventsChannel = new NotificationChannel(
					Constants.CHANNEL_ID_NETWORK_EVENTS,
					getString(R.string.channel_name_network_events),
					NotificationManager.IMPORTANCE_DEFAULT
			);
			networkEventsChannel.setDescription(getString(R.string.channel_description_network_events));
			networkEventsChannel.enableVibration(true);
			networkEventsChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null);

			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			if (notificationManager != null) {
				notificationManager.createNotificationChannel(channel);
				notificationManager.createNotificationChannel(messagesChannel);
				notificationManager.createNotificationChannel(networkEventsChannel);
				Log.d(TAG, "The channels are created.");
			}
		}
	}

	/**
	 * Checks if the host node is configured and, if so, initializes the communication modules.
	 * This method can be called multiple times; it will not re-initialize if already done.
	 */
	private synchronized void checkAndInitializeCommunicationModules() {
		if (areCommunicationModulesInitialized()) {
			Log.d(TAG, "Communication modules already initialized.");
			return;
		}

		serviceExecutor.execute(() -> {
			boolean isSetupComplete = sharedPreferences.getBoolean(Constants.PREF_KEY_IS_SETUP_COMPLETE, false);

			if (isSetupComplete) {
				long hostNodeId = sharedPreferences.getLong(Constants.PREF_KEY_HOST_NODE_ID, -1L);
				String hostAddressName = sharedPreferences.getString(Constants.PREF_KEY_HOST_ADDRESS_NAME, null);

				if (hostNodeId != -1L && hostAddressName != null) {
					hostNode = appDatabase.nodeDao().getNodeByIdSync(hostNodeId);
					if (hostNode != null) {
						Log.d(TAG, "Host node loaded in service: " + hostNode.getDisplayName());
						// Actual initialization of communication managers
						initializeCommunicationManagers();
						Log.i(TAG, "Communication modules initialized successfully.");
					} else {
						Log.e(TAG, "Host node not found in DB from service despite SharedPreferences entry. State inconsistent.");
						sharedPreferences.edit().putBoolean(Constants.PREF_KEY_IS_SETUP_COMPLETE, false).apply();
					}
				} else {
					Log.e(TAG, "SharedPreferences corrupt or incomplete for host node in service. State inconsistent.");
				}
			} else {
				Log.d(TAG, "Host node not set up yet. Communication modules not initialized.");
			}
		});
	}

	/**
	 * Initializes instances of NearbyManager, SignalManager, and NearbySignalMessenger.
	 * This method should only be called once the hostNode is defined and loaded.
	 */
	private void initializeCommunicationManagers() {
		if (hostNode == null) {
			Log.e(TAG, "Host node is null. Cannot initialize communication managers.");
			return;
		}
		// Reset node connection state
		new NodeRepository(getApplicationContext()).setAllNodesDisconnected();
		if (nearbyManager == null) {
			// Pass the service's application context and the host node's address name
			nearbyManager = NearbyManager.getInstance(getApplicationContext(), hostNode.getAddressName());
		}
		if (signalManager == null) {
			// Pass the service's application context
			signalManager = SignalManager.getInstance(getApplicationContext());
			// Ensure SignalManager is initialized with keys if necessary
			signalManager.initialize(new SignalManager.SignalInitializationCallback() {
				@Override
				public void onSuccess() {
					Log.d(TAG, "SignalManager initialized in service.");
				}

				@Override
				public void onError(Exception e) {
					Log.e(TAG, "SignalManager initialization failed in service: " + e.getMessage());
				}
			});
		}
		if (nearbySignalMessenger == null) {
			nearbySignalMessenger = NearbySignalMessenger.getInstance(
					getApplicationContext(),
					nearbyManager,
					signalManager,
					hostNode // Pass the host node
			);
		}

		// Start Nearby Connections advertising/discovery after initialization
		// No specific UI action on advertising start/fail needed here
		new android.os.Handler(getMainLooper()).post(() -> {
			try {
				Log.d(TAG, "Attempting to start NearbyManager advertising and discovery on main thread.");
				nearbyManager.startAdvertising();
				nearbyManager.startDiscovery();
				Log.d(TAG, "NearbyManager advertising and discovery successfully started.");
				Log.d(TAG, "NearbyManager started in service.");
			} catch (Exception e) {
				Log.w(TAG, "Failed to start NearbyManager operations on main thread: " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Stops the communication managers (Nearby, Signal, etc.).
	 * Useful when stopping the service or if communication needs to be suspended.
	 */
	private synchronized void stopCommunicationModules() {
		if (nearbySignalMessenger != null) {
			nearbySignalMessenger.shutdown();
		}
		nearbySignalMessenger = null; // Release the instance
		if (nearbyManager != null) {
			nearbyManager.stopNearby();
			nearbyManager = null; // Release the instance
			Log.d(TAG, "NearbyManager stopped in service.");
		}
		signalManager = null;
		new NodeRepository(getApplicationContext()).setAllNodesDisconnected();
		Log.i(TAG, "Communication modules stopped.");
	}

	/**
	 * Creates a PendingIntent to launch {@link MainActivity} with custom data.
	 * This method uses the application context to ensure the PendingIntent
	 * can outlive the current component if needed.
	 *
	 * @param data        A {@link Bundle} containing any extra data to be passed to the {@link MainActivity}.
	 *                    This bundle will be set as the extras of the {@link Intent}.
	 * @param requestCode A unique request code for this PendingIntent. It is also interpreted as the ID of
	 *                    notification to which the returned {@code PendingIntent} is to bind.
	 *                    This helps differentiate between multiple {@code PendingIntent}s from the
	 *                    application, especially when they might lead to different actions or views
	 *                    within the MainActivity.
	 * @param groupId     The ID of the notification group the individual notification belongs to.
	 * @param groupKey    The string key identifying the notification group the individual notification belongs to.
	 * @return A {@link PendingIntent} configured to open {@link MainActivity} with the specified data.
	 */
	private PendingIntent createMainActivityPendingIntent(@NonNull NotificationType notificationType, @NonNull Bundle data, int requestCode, int groupId, @NonNull String groupKey) {
		Intent intent = new Intent(getApplicationContext(), MainActivity.class);
		intent.putExtras(data);
		putDismissData(intent, requestCode, groupId, groupKey);
		intent.putExtra(Constants.EXTRA_NOTIFICATION_TYPE, notificationType.name());
		intent.setAction(Constants.ACTION_LAUNCH_FROM_NOTIFICATION); // Rewrite the action
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

		// FLAG_IMMUTABLE is required for API 31+
		int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

		return PendingIntent.getActivity(getApplicationContext(), requestCode, intent, flags);
	}

	/**
	 * Adds relevant notification dismissal data as extras to an Intent.
	 * This method is typically used to prepare an Intent that will be sent to a BroadcastReceiver
	 * (e.g., {@link NotificationDismissReceiver}) when a notification is dismissed by the user or the system.
	 * This method also set default action to the Intent to value {@link Constants#ACTION_NOTIFICATION_DISMISSED}
	 *
	 * @param intent         The Intent to which the dismissal data will be added. This Intent should
	 *                       be the one used to create the PendingIntent for the notification's deleteIntent.
	 * @param notificationId The unique ID of the specific notification being dismissed.
	 *                       If this Intent is for a group summary notification's deleteIntent,
	 *                       this should be the ID of the summary notification.
	 * @param groupId        The ID of the notification group (summary notification's ID) to which the
	 *                       dismissed notification belongs. This is useful for managing grouped notifications
	 *                       when a child notification is dismissed, or for confirmation if the group summary
	 *                       itself is dismissed.
	 * @param groupKey       The unique string key representing the notification group.
	 *                       This helps identify which entity the dismissal relates to.
	 */
	private void putDismissData(@NonNull Intent intent, int notificationId, int groupId, @NonNull String groupKey) {
		intent.putExtra(Constants.NOTIFICATION_ID, notificationId);
		intent.putExtra(Constants.NOTIFICATION_GROUP_ID, groupId);
		intent.putExtra(Constants.NOTIFICATION_GROUP_KEY, groupKey);
		intent.setAction(Constants.ACTION_NOTIFICATION_DISMISSED);
	}

	/**
	 * Builds a {@link NotificationCompat.Builder} for a group summary notification.
	 * This summary notification acts as the collapsed view for a stack of individual notifications
	 * belonging to the same group.
	 *
	 * @param channelId    The ID of the notification channel this summary notification will use.
	 * @param groupKey     The unique string key that identifies the group of notifications.
	 *                     All individual notifications belonging to this group must use the same groupKey.
	 * @param contentTitle The main title displayed on the collapsed summary notification.
	 * @param summaryText  The summary text displayed within the InboxStyle when the notification is expanded.
	 *                     This typically provides a count or a brief overview of the notifications in the group.
	 * @param groupData    A GroupData object containing the unique integer ID for this group summary
	 *                     and its current children count. The {@code groupData.id} is used as the notification ID
	 *                     for the summary itself and for its associated dismiss {@link PendingIntent}.
	 * @return A configured NotificationCompat.Builder instance for the group summary notification.
	 */
	private NotificationCompat.Builder summaryBuilder(@NonNull String channelId, @NonNull String groupKey,
	                                                  @Nullable String contentTitle, @Nullable String summaryText,
	                                                  @NonNull GroupData groupData) {
		Intent summaryDismissIntent = new Intent(this, NotificationDismissReceiver.class);
		putDismissData(summaryDismissIntent, groupData.id, groupData.id, groupKey);

		return new NotificationCompat.Builder(this, channelId)
				.setSmallIcon(R.drawable.ic_notification)
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setGroupSummary(true)
				.setGroup(groupKey)
				.setAutoCancel(true)
				.setContentTitle(contentTitle)
				.setStyle(new NotificationCompat.InboxStyle()
						.setSummaryText(summaryText))
				.setDeleteIntent(PendingIntent.getBroadcast(this, idProvider.nextId(),
						summaryDismissIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
	}

	/**
	 * Builds a {@code NotificationCompat.Builder} for an individual (child) notification within a group.
	 * This notification will be stacked under a group summary notification.
	 *
	 * @param notificationType  The type of notification, used for handling its action.
	 * @param channelId         The ID of the notification channel.
	 * @param pendingIntentData Data to be included in the PendingIntent that launches the main activity when clicked.
	 * @param notificationId    The unique ID for this specific individual notification.
	 * @param groupKey          The string key identifying the notification group it belongs to.
	 * @param groupData         A GroupData object containing the group's ID and current child count.
	 * @param contentTitle      The title of the individual notification.
	 * @param contentText       The main text content of the individual notification (e.g., message preview).
	 * @param priority          The priority level for this notification.
	 * @return A configured NotificationCompat.Builder for the child notification.
	 */
	private NotificationCompat.Builder childBuilder(@NonNull NotificationType notificationType, @NonNull String channelId,
	                                                @NonNull Bundle pendingIntentData, int notificationId,
	                                                @NonNull String groupKey, @NonNull GroupData groupData,
	                                                @Nullable String contentTitle, @Nullable String contentText, int priority) {
		Intent dismissIntent = new Intent(this, NotificationDismissReceiver.class);
		putDismissData(dismissIntent, notificationId, groupData.id, groupKey);

		PendingIntent pendingIntent = createMainActivityPendingIntent(notificationType, pendingIntentData, notificationId, groupData.id, groupKey);

		return new NotificationCompat.Builder(this, channelId)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle(contentTitle).setContentText(contentText)
				.setPriority(priority).setContentIntent(pendingIntent).setAutoCancel(true)
				.setGroup(groupKey).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
				.setDeleteIntent(PendingIntent.getBroadcast(this, idProvider.nextId(), dismissIntent,
						PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
	}

	/**
	 * Displays a notification for a new message received.
	 * Extracts message details from the provided data bundle and constructs the notification.
	 *
	 * @param data The Bundle containing message sender name, content, remote node address, and message ID.
	 */
	private void showNewMessageNotification(@NonNull Bundle data) {
		String senderName = data.getString(Constants.MESSAGE_SENDER_NAME);
		String messageContent = data.getString(Constants.MESSAGE_CONTENT);
		String remoteNodeAddress = data.getString(Constants.MESSAGE_SENDER_ADDRESS);
		long messageId = data.getLong(Constants.MESSAGE_ID, -1L);
		long payloadId = data.getLong(Constants.MESSAGE_PAYLOAD_ID, 0L);

		if (senderName == null || messageContent == null || remoteNodeAddress == null
				|| messageId == -1L || payloadId == 0L) {
			Log.e(TAG, "Missing data for NEW_MESSAGE notification: senderName=" + senderName
					+ ", content=" + messageContent + ", address=" + remoteNodeAddress);
			return;
		}

		GroupData summary = idProvider.addGroup(remoteNodeAddress, remoteNodeAddress.hashCode());
		int notificationId = idProvider.nextId();

		// Prepare the individual notification
		Intent markAsReadIntent = new Intent(this, MessageBroadcastReceiver.class);
		markAsReadIntent.putExtras(data);
		putDismissData(markAsReadIntent, notificationId, summary.id, remoteNodeAddress);
		markAsReadIntent.setAction(Constants.ACTION_BROADCAST_MASSAGE_NOTIFICATION); // Rewrite action
		markAsReadIntent.addCategory(Constants.CATEGORY_MARK_AS_READ);

		PendingIntent markAsReadPendingIntent = PendingIntent.getBroadcast(
				this,
				idProvider.nextId(),
				markAsReadIntent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
		);

		NotificationCompat.Action markAsReadAction = new NotificationCompat.Action.Builder(
				null,
				getString(R.string.action_mark_as_read),
				markAsReadPendingIntent
		).build();

		NotificationCompat.Builder builder = childBuilder(NotificationType.NEW_MESSAGE, Constants.CHANNEL_ID_MESSAGES, data, notificationId,
				remoteNodeAddress, summary, senderName, messageContent, NotificationCompat.PRIORITY_HIGH)
				.addAction(markAsReadAction);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
			builder.setVibrate(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
		}

		String title = getString(R.string.new_messages_from_sender,
				getResources().getQuantityString(R.plurals.new_message_count, summary.childrenCount, summary.childrenCount),
				senderName);
		NotificationCompat.Builder summaryNotificationBuilder = summaryBuilder(Constants.CHANNEL_ID_MESSAGES, remoteNodeAddress, senderName, title, summary);

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
		try {
			notificationManager.notify(notificationId, builder.build());
			notificationManager.notify(summary.id, summaryNotificationBuilder.build());
			Log.d(TAG, "New message notification shown for " + senderName);
		} catch (SecurityException e) {
			Log.w(TAG, "User has cancelled notification post permission", e);
		}
	}

	/**
	 * Displays a notification for a new node discovered.
	 * Extracts node details from the provided data bundle and constructs the notification.
	 *
	 * @param data The Bundle containing the discovered node's display name, address, and new status.
	 */
	private void showNewNodeDiscoveredNotification(@NonNull Bundle data) {
		String nodeName = data.getString(Constants.NODE_DISPLAY_NAME);
		String nodeAddress = data.getString(Constants.NODE_ADDRESS);
		boolean isNew = data.getBoolean(Constants.NODE_IS_NEW);

		if (nodeAddress == null) {
			Log.e(TAG, "Missing data for NEW_NODE_DISCOVERED notification: name=" + nodeName + ", address is null");
			return;
		}

		String groupKey = Constants.GROUP_NODE_DISCOVERY_KEY;
		GroupData summary = idProvider.addGroup(groupKey, groupKey.hashCode());
		int notificationId = idProvider.nextId();

		int title = isNew ? R.string.notification_title_new_node_discovered : R.string.notification_title_node_discovered;
		int content = isNew ? R.string.notification_content_new_node_discovered : R.string.notification_content_node_discovered;
		NotificationCompat.Builder builder = childBuilder(NotificationType.NEW_NODE_DISCOVERED, Constants.CHANNEL_ID_NETWORK_EVENTS,
				data, notificationId, groupKey, summary, getString(title), getString(content, nodeName != null ? nodeName : nodeAddress),
				NotificationCompat.PRIORITY_DEFAULT);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
			builder.setVibrate(new long[]{100, 200, 300});
		}

		NotificationCompat.Builder summaryBuilder = summaryBuilder(Constants.CHANNEL_ID_NETWORK_EVENTS, groupKey,
				getString(R.string.notification_title_node_discovery),
				getResources().getQuantityString(R.plurals.node_count, summary.childrenCount, summary.childrenCount), summary);

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
		try {
			notificationManager.notify(notificationId, builder.build());
			notificationManager.notify(summary.id, summaryBuilder.build());
			Log.d(TAG, "New node discovered notification shown for " + nodeName);
		} catch (SecurityException e) {
			Log.w(TAG, "User has cancelled notification post permission", e);
		}
	}

	/**
	 * Displays a notification indicating the initiation of a route discovery process.
	 * Extracts the target node's address from the provided data bundle and constructs the notification.
	 *
	 * @param data The Bundle containing the target node's address and display name.
	 */
	private void showRouteDiscoveryInitiatedNotification(@NonNull Bundle data) {
		String targetAddress = data.getString(Constants.NODE_ADDRESS);
		String displayName = data.getString(Constants.NODE_DISPLAY_NAME);

		if (targetAddress == null) {
			Log.e(TAG, "Missing data for ROUTE_DISCOVERY_INITIATED notification: address is null.");
			return;
		}

		String groupKey = Constants.GROUP_ROUTE_DISCOVERY_KEY;
		GroupData groupData = idProvider.addGroup(groupKey, groupKey.hashCode());
		int notificationId = idProvider.nextId();

		NotificationCompat.Builder builder = childBuilder(NotificationType.ROUTE_DISCOVERY_INITIATED, Constants.CHANNEL_ID_NETWORK_EVENTS, data, notificationId, groupKey,
				groupData, getString(R.string.notification_title_route_discovery_initiated),
				getString(R.string.notification_content_route_discovery_initiated,
						displayName != null ? displayName : targetAddress), NotificationCompat.PRIORITY_LOW);

		// No sound/vibration for low priority on older APIs unless explicitly set here

		String title = getString(R.string.notification_summary_title_route_discovery);
		NotificationCompat.Builder summaryBuilder = summaryBuilder(Constants.CHANNEL_ID_NETWORK_EVENTS, groupKey, title, title, groupData);

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
		try {
			notificationManager.notify(notificationId, builder.build());
			notificationManager.notify(groupData.id, summaryBuilder.build());
			Log.d(TAG, "Route discovery initiated notification shown for " + targetAddress);
		} catch (SecurityException e) {
			Log.w(TAG, "User has cancelled notification post permission", e);
		}
	}

	/**
	 * Displays a notification indicating the result of a route discovery (found or not found).
	 * Extracts the target node's address and the discovery outcome from the provided data bundle,
	 * then constructs the appropriate notification.
	 *
	 * @param data The Bundle containing the target node's address, display name, and route found status.
	 */
	private void showRouteDiscoveryResultNotification(@NonNull Bundle data) {
		String address = data.getString(Constants.NODE_ADDRESS);
		String displayName = data.getString(Constants.NODE_DISPLAY_NAME);
		boolean found = data.getBoolean(Constants.ROUTE_IS_FOUND);

		if (address == null) {
			Log.e(TAG, "Missing data for ROUTE_DISCOVERY_RESULT notification: address is null");
			return;
		}

		String groupKey = Constants.GROUP_ROUTE_DISCOVERY_KEY; // Same group as for route initiation
		GroupData groupData = idProvider.addGroup(groupKey, groupKey.hashCode());
		int notificationId = idProvider.nextId();

		String title;
		String content;
		int priority;
		if (found) {
			title = getString(R.string.notification_title_route_found);
			content = getString(R.string.notification_content_route_found, displayName != null ? displayName : address);
			priority = NotificationCompat.PRIORITY_DEFAULT;
		} else {
			title = getString(R.string.notification_title_route_not_found);
			content = getString(R.string.notification_content_route_not_found, displayName != null ? displayName : address);
			priority = NotificationCompat.PRIORITY_HIGH; // More urgent for failure
		}

		NotificationCompat.Builder builder = childBuilder(NotificationType.ROUTE_DISCOVERY_RESULT, Constants.CHANNEL_ID_NETWORK_EVENTS, data, notificationId, groupKey,
				groupData, title, content, priority);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			if (found) {
				builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
			} else {
				builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)); // More distinct sound for failure
				builder.setVibrate(new long[]{0, 500, 100, 500}); // Stronger vibration for failure
			}
		}

		String summaryTitle = getString(R.string.notification_summary_title_route_discovery);
		NotificationCompat.Builder summaryBuilder = summaryBuilder(Constants.CHANNEL_ID_NETWORK_EVENTS, groupKey, summaryTitle, summaryTitle, groupData);

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
		try {
			notificationManager.notify(notificationId, builder.build());
			notificationManager.notify(groupData.id, summaryBuilder.build());
			Log.d(TAG, "Route discovery result notification shown for " + address + ": " + (found ? "Success" : "Failure"));
		} catch (SecurityException e) {
			Log.w(TAG, "User has cancelled notification post permission", e);
		}
	}

	/**
	 * Manages unique integer IDs for both individual notifications and notification groups (summaries).
	 * It ensures that notification IDs do not conflict and that group IDs are consistently managed.
	 *
	 * @author hovozounkou
	 */
	private static class NotificationIdProvider {

		private static final int INITIAL_ID = 10; // Starting point for sequential notification IDs.

		private final Map<String, GroupData> groups; // Stores GroupData objects mapped by their group key
		private volatile int nextId; // The next available sequential ID for a notification.

		/**
		 * Initializes a new NotificationIdProvider.
		 */
		public NotificationIdProvider() {
			groups = new ConcurrentHashMap<>();
			nextId = INITIAL_ID;
		}

		/**
		 * Adds or updates a notification group and returns its associated GroupData.
		 * If the group already exists, its child count is incremented. If it's a new group,
		 * it attempts to use the provided {@code groupId} as its unique ID.
		 * If {@code groupId} conflicts with an already allocated sequential ID, a new sequential ID is used.
		 *
		 * @param group   The unique string key for the group.
		 * @param groupId A proposed integer ID for the group, typically derived from its unique key.
		 * @return The {@code GroupData} object for the specified group, including its stable ID and child count.
		 */
		@NonNull
		public synchronized GroupData addGroup(@NonNull String group, int groupId) {
			if (groups.containsKey(group)) {
				GroupData gd = Objects.requireNonNull(groups.get(group));
				gd.childrenCount++;
				return gd;
			}
			int finalGroup;
			if (groupId >= INITIAL_ID && groupId <= nextId) {
				// This value is already used, change it
				finalGroup = nextId();
			} else {
				finalGroup = groupId;
			}
			GroupData data = new GroupData(finalGroup);
			groups.put(group, data);
			return data;
		}

		/**
		 * Removes a notification group from tracking.
		 * If no groups remain, the sequential ID counter (`nextId`) is reset.
		 *
		 * @param group   The unique string key of the group to remove.
		 * @param context The application context, used to access {@link NotificationManagerCompat}.
		 */
		public synchronized void removeGroup(@NonNull String group, Context context) {
			GroupData data = groups.remove(group);
			if (groups.isEmpty()) {
				nextId = INITIAL_ID;
			}
			if (data != null) {
				// Ensure the bound notification is really cancelled
				NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
				notificationManager.cancel(data.id);
			}
		}

		/**
		 * Decrements the child count for a specified notification group.
		 * If the child count reaches zero, the group is removed, and its corresponding
		 * summary notification is cancelled from the system notification bar.
		 *
		 * @param groupKey The unique string key of the group.
		 * @param context  The application context, used to access {@link NotificationManagerCompat}.
		 */
		public synchronized void decreaseGroupChildrenCount(@NonNull String groupKey, Context context) {
			GroupData groupData = groups.get(groupKey);
			if (groupData != null) {
				groupData.childrenCount--;
				if (groupData.childrenCount == 0) {
					removeGroup(groupKey, context);
				}
			}
		}

		/**
		 * Generates a unique integer ID for a notification.
		 * This method ensures that the generated ID does not conflict with any
		 * currently used group notification IDs, nor any used notification ID
		 * for an individual notification.
		 *
		 * @return A unique integer ID suitable for a notification.
		 */
		public synchronized int nextId() {
			Set<Integer> ids = groups.values().stream().map(groupData -> groupData.id).collect(Collectors.toSet());
			int next = nextId;
			while (ids.contains(next)) {
				next++;
			}
			nextId = next + 1;
			return next;
		}
	}

	/**
	 * Represents the data for a notification group, tracking its unique ID
	 * and the number of child notifications within it.
	 *
	 * @author hovozounkou
	 */
	private static class GroupData {
		/**
		 * The unique integer ID assigned to this notification group.
		 * This ID is used for the group summary notification.
		 */
		public final int id;
		/**
		 * The current count of individual (child) notifications belonging to this group.
		 */
		public int childrenCount;

		/**
		 * Constructs a new GroupData instance.
		 *
		 * @param id The unique ID for this group.
		 */
		public GroupData(int id) {
			this.id = id;
			childrenCount = 1; // Initialize with 1 child as it's typically created when the first child arrives
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			GroupData groupData = (GroupData) o;
			return id == groupData.id && childrenCount == groupData.childrenCount;
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, childrenCount);
		}
	}
}
