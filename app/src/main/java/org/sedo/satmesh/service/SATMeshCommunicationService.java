package org.sedo.satmesh.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SATMeshCommunicationService extends Service {

	// Intent Actions for the service
	public static final String ACTION_START_FOREGROUND_SERVICE = "org.sedo.satmesh.action.START_FOREGROUND_SERVICE";
	public static final String ACTION_STOP_FOREGROUND_SERVICE = "org.sedo.satmesh.action.STOP_FOREGROUND_SERVICE";
	public static final String ACTION_INITIALIZE_COMMUNICATION_MODULES = "org.sedo.satmesh.action.INITIALIZE_COMMUNICATION_MODULES";
	public static final String ACTION_STOP_COMMUNICATION_MODULES = "org.sedo.satmesh.action.STOP_COMMUNICATION_MODULES";
	private static final String TAG = "SATMeshCommService";
	private static final String NOTIFICATION_CHANNEL_ID = "SATMesh_Communication_Channel";
	private static final int NOTIFICATION_ID = 101; // Unique ID for the foreground service notification
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
	 * @param requestCode A unique request code for this PendingIntent. This helps differentiate
	 *                    between multiple {@code PendingIntent}s from the application, especially when
	 *                    they might lead to different actions or views within the MainActivity.
	 * @return A {@link PendingIntent} configured to open {@link MainActivity} with the specified data.
	 */
	private PendingIntent createMainActivityPendingIntent(@NonNull NotificationType notificationType, Bundle data, int requestCode) {
		Intent intent = new Intent(getApplicationContext(), MainActivity.class);
		intent.putExtras(data);
		intent.putExtra(Constants.EXTRA_NOTIFICATION_TYPE, notificationType);
		intent.setAction(Constants.ACTION_LAUNCH_FROM_NOTIFICATION);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

		// FLAG_IMMUTABLE is required for API 31+
		int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

		return PendingIntent.getActivity(getApplicationContext(), requestCode, intent, flags);
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

		if (senderName == null || messageContent == null || remoteNodeAddress == null) {
			Log.e(TAG, "Missing data for NEW_MESSAGE notification: senderName=" + senderName + ", content=" + messageContent + ", address=" + remoteNodeAddress);
			return;
		}

		PendingIntent pendingIntent = createMainActivityPendingIntent(NotificationType.NEW_MESSAGE, data, Constants.NOTIFICATION_ID_NEW_MESSAGE);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.CHANNEL_ID_MESSAGES)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle(senderName)
				.setContentText(messageContent)
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setContentIntent(pendingIntent)
				.setAutoCancel(true);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
			builder.setVibrate(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
		}

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
		try {
			notificationManager.notify(Constants.NOTIFICATION_ID_NEW_MESSAGE, builder.build());
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

		if (nodeName == null || nodeAddress == null) {
			Log.e(TAG, "Missing data for NEW_NODE_DISCOVERED notification: name=" + nodeName + ", address=" + nodeAddress);
			return;
		}

		PendingIntent pendingIntent = createMainActivityPendingIntent(NotificationType.NEW_NODE_DISCOVERED,
				data, Constants.NOTIFICATION_ID_NEW_NODE_DISCOVERED);

		int title = isNew ? R.string.notification_title_new_node_discovered : R.string.notification_title_node_discovered;
		int content = isNew ? R.string.notification_content_new_node_discovered : R.string.notification_content_node_discovered;
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.CHANNEL_ID_NETWORK_EVENTS)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle(getString(title))
				.setContentText(getString(content, nodeName))
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setContentIntent(pendingIntent)
				.setAutoCancel(true);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
			builder.setVibrate(new long[]{100, 200, 300});
		}

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
		try {
			notificationManager.notify(Constants.NOTIFICATION_ID_NEW_NODE_DISCOVERED, builder.build());
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
		String targetDisplayName = data.getString(Constants.NODE_DISPLAY_NAME);

		if (targetAddress == null) {
			Log.e(TAG, "Missing data for ROUTE_DISCOVERY_INITIATED notification: address is null.");
			return;
		}

		PendingIntent pendingIntent = createMainActivityPendingIntent(NotificationType.ROUTE_DISCOVERY_INITIATED,
				data, Constants.NOTIFICATION_ID_ROUTE_DISCOVERY_INITIATED);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.CHANNEL_ID_NETWORK_EVENTS)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle(getString(R.string.notification_title_route_discovery_initiated))
				.setContentText(getString(R.string.notification_content_route_discovery_initiated, targetDisplayName != null ? targetDisplayName : targetAddress))
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setContentIntent(pendingIntent)
				.setAutoCancel(true);

		// No sound/vibration for low priority on older APIs unless explicitly set here

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
		try {
			notificationManager.notify(Constants.NOTIFICATION_ID_ROUTE_DISCOVERY_INITIATED, builder.build());
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
		String targetAddress = data.getString(Constants.NODE_ADDRESS);
		String targetDisplayName = data.getString(Constants.NODE_DISPLAY_NAME);
		boolean found = data.getBoolean(Constants.ROUTE_IS_FOUND);

		if (targetAddress == null) {
			Log.e(TAG, "Missing data for ROUTE_DISCOVERY_RESULT notification: address is null");
			return;
		}

		PendingIntent pendingIntent = createMainActivityPendingIntent(NotificationType.ROUTE_DISCOVERY_RESULT,
				data, Constants.NOTIFICATION_ID_ROUTE_DISCOVERY_RESULT);

		String title;
		String content;
		int priority;

		if (found) {
			title = getString(R.string.notification_title_route_found);
			content = getString(R.string.notification_content_route_found, targetDisplayName != null ? targetDisplayName : targetAddress);
			priority = NotificationCompat.PRIORITY_DEFAULT;
		} else {
			title = getString(R.string.notification_title_route_not_found);
			content = getString(R.string.notification_content_route_not_found, targetDisplayName != null ? targetDisplayName : targetAddress);
			priority = NotificationCompat.PRIORITY_HIGH; // More urgent for failure
		}

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.CHANNEL_ID_NETWORK_EVENTS)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle(title)
				.setContentText(content)
				.setPriority(priority)
				.setContentIntent(pendingIntent)
				.setAutoCancel(true);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			if (found) {
				builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
			} else {
				builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)); // More distinct sound for failure
				builder.setVibrate(new long[]{0, 500, 100, 500}); // Stronger vibration for failure
			}
		}

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
		try {
			notificationManager.notify(Constants.NOTIFICATION_ID_ROUTE_DISCOVERY_RESULT, builder.build()); // Fixed ID, updates previous
			Log.d(TAG, "Route discovery result notification shown for " + targetAddress + ": " + (found ? "Success" : "Failure"));
		} catch (SecurityException e) {
			Log.w(TAG, "User has cancelled notification post permission", e);
		}
	}
}
