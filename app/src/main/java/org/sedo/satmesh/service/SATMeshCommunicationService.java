package org.sedo.satmesh.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.common.api.Status;

import org.sedo.satmesh.AppDatabase;
import org.sedo.satmesh.MainActivity;
import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.nearby.NearbySignalMessenger;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.NodeState;
import org.sedo.satmesh.ui.data.NodeTransientStateRepository;
import org.sedo.satmesh.utils.Constants;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SATMeshCommunicationService extends Service implements NearbyManager.DeviceConnectionListener {

	// Intent Actions for the service
	public static final String ACTION_START_FOREGROUND_SERVICE = "org.sedo.satmesh.action.START_FOREGROUND_SERVICE";
	public static final String ACTION_STOP_FOREGROUND_SERVICE = "org.sedo.satmesh.action.STOP_FOREGROUND_SERVICE";
	public static final String ACTION_INITIALIZE_COMMUNICATION_MODULES = "org.sedo.satmesh.action.INITIALIZE_COMMUNICATION_MODULES";
	public static final String ACTION_STOP_COMMUNICATION_MODULES = "org.sedo.satmesh.action.STOP_COMMUNICATION_MODULES";
	private static final String TAG = "SATMeshCommService";
	private static final String NOTIFICATION_CHANNEL_ID = "SatMesh_Communication_Channel";
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
		createNotificationChannel();

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
		return signalManager != null && nearbyManager != null && nearbySignalMessenger != null;
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
						stopForeground(true);
						stopSelf();
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
		// If you need bidirectional communication between UI and service,
		// you would implement a Binder or Messenger interface here.
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
	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = getString(R.string.channel_name);
			String description = getString(R.string.channel_description);
			int importance = NotificationManager.IMPORTANCE_LOW; // Low importance for a background notification

			NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
			channel.setDescription(description);

			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			if (notificationManager != null) {
				notificationManager.createNotificationChannel(channel);
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
					hostNode = appDatabase.nodeDao().getNodeById(hostNodeId);
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
		if (nearbyManager == null) {
			// Pass the service's application context and the host node's address name
			nearbyManager = NearbyManager.getInstance(getApplicationContext(), hostNode.getAddressName());
			nearbyManager.addDeviceConnectionListener(this);
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
					// Pass the database instance
					hostNode // Pass the host node
			);
		}

		// Start Nearby Connections advertising/discovery after initialization
		// No specific UI action on advertising start/fail needed here
		nearbyManager.startAdvertising(null, null);
		nearbyManager.startDiscovery(
				this::handleEndpointFound,
				this::handleEndpointLost,
				null,
				e -> Log.e(TAG, "Failed to start discovery: " + e.getMessage())
		);
		Log.d(TAG, "NearbyManager started in service.");
	}

	/**
	 * Handles the event when a new Nearby endpoint is found.
	 * Decides whether to initiate a connection based on the current connection state.
	 *
	 * @param endpointId   The ID of the discovered endpoint.
	 * @param endpointName The Signal Protocol address name of the discovered endpoint.
	 */
	private void handleEndpointFound(@NonNull String endpointId, @NonNull String endpointName) {
		Log.d(TAG, "handleEndpointFound: Discovered " + endpointName + " (ID: " + endpointId + ")");

		// Check if we are already connected to this endpoint's address.
		if (nearbyManager.isAddressDirectlyConnected(endpointName)) {
			Log.i(TAG, "Already connected to " + endpointName + ". Skipping connection attempt.");
			return;
		}

		/*
		 * Check if a connection request is already pending for this endpoint,
		 * or if an incoming connection has been initiated by this endpoint.
		 * We use the NearbyManager's internal maps for these checks.
		 */
		if (nearbyManager.getAllPendingAddressName().contains(endpointName) ||
				nearbyManager.getAllIncomingAddressName().contains(endpointName)) {
			Log.i(TAG, "Connection is already pending or incoming for " + endpointName + ". No new request initiated.");
			return;
		}

		// === Decision point: Initiate a connection ===
		// We decide to request a connection to any discovered peer if no active, pending,
		// or incoming connection exists for that specific Signal Protocol address name.
		NodeTransientStateRepository.getInstance().updateTransientNodeState(endpointName, NodeState.ON_ENDPOINT_FOUND);
		Log.i(TAG, "Attempting to request connection to " + endpointName + " (ID: " + endpointId + ")");
		nearbyManager.requestConnection(endpointId, endpointName, (id, success) -> {
			if (success) {
				Log.d(TAG, "Connection request sent to " + endpointName);
				// The actual connection success/failure will be handled by
				// the onConnectionResult callback in NearbyManager's ConnectionLifecycleCallback.
			} else {
				Log.e(TAG, "Failed to send connection request to " + endpointName);
			}
		});
	}

	/**
	 * Handles the event when a Nearby endpoint is lost (goes out of range).
	 * This method primarily serves for logging and potentially notifying other components,
	 * as NearbyManager's own onDisconnected callback handles active connections.
	 *
	 * @param endpointId   The ID of the lost endpoint.
	 * @param endpointName The Signal Protocol address name of the lost endpoint.
	 */
	private void handleEndpointLost(@NonNull String endpointId, @NonNull String endpointName) {
		Log.d(TAG, "handleEndpointLost: Lost " + endpointName + " (ID: " + endpointId + ")");
		/*
		 * This method is primarily for notification and logging that a discovered endpoint is no
		 * longer available. The NearbyManager's internal mechanisms and its DeviceConnectionListener
		 * will handle the actual state changes for connected or actively pending endpoints.
		 */
		NodeTransientStateRepository.getInstance().updateTransientNodeState(endpointName, NodeState.ON_ENDPOINT_LOST);
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
			nearbyManager.removeDeviceConnectionListener(this);
			nearbyManager.stopNearby();
			nearbyManager = null; // Release the instance
			Log.d(TAG, "NearbyManager stopped in service.");
		}
		signalManager = null;
		Log.i(TAG, "Communication modules stopped.");
	}

	// Implementation of `NearbyManager.DeviceConnectionListener`
	@Override
	public void onConnectionInitiated(String endpointId, String deviceAddressName) {
		// For simplicity, we auto-accept initiated connections.
		nearbyManager.acceptConnection(endpointId, (payload, success) -> {
			if (success) {
				Log.d(TAG, "Accepted connection to " + deviceAddressName);
			} else {
				Log.e(TAG, "Failed to accept connection to " + deviceAddressName);
			}
		});
	}

	@Override
	public void onDeviceConnected(String endpointId, String deviceAddressName) {
		// pass
	}

	@Override
	public void onConnectionFailed(String deviceAddressName, Status status) {
		// pass
	}

	@Override
	public void onDeviceDisconnected(String endpointId, String deviceAddressName) {
		// pass
	}
}
