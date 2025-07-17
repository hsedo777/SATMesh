package org.sedo.satmesh;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.sedo.satmesh.databinding.ActivityMainBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.service.SATMeshCommunicationService;
import org.sedo.satmesh.service.SATMeshServiceStatus;
import org.sedo.satmesh.ui.AppHomeListener;
import org.sedo.satmesh.ui.ChatFragment;
import org.sedo.satmesh.ui.ChatListAccessor;
import org.sedo.satmesh.ui.ChatListFragment;
import org.sedo.satmesh.ui.DiscussionListener;
import org.sedo.satmesh.ui.DiscussionMenuListener;
import org.sedo.satmesh.ui.KnownNodesFragment;
import org.sedo.satmesh.ui.LoadingFragment;
import org.sedo.satmesh.ui.LoadingFragment.ServiceLoadingListener;
import org.sedo.satmesh.ui.NearbyDiscoveryFragment;
import org.sedo.satmesh.ui.NearbyDiscoveryListener;
import org.sedo.satmesh.ui.SearchFragment;
import org.sedo.satmesh.ui.WelcomeFragment;
import org.sedo.satmesh.ui.WelcomeFragment.OnWelcomeCompletedListener;
import org.sedo.satmesh.utils.Constants;
import org.sedo.satmesh.utils.NotificationType;

import java.util.Objects;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity implements OnWelcomeCompletedListener,
		DiscussionListener, NearbyDiscoveryListener, ChatListAccessor, AppHomeListener,
		DiscussionMenuListener, ServiceLoadingListener {

	/**
	 * These permissions are required before connecting to Nearby Connections.
	 */
	private static final String[] REQUIRED_PERMISSIONS;
	private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;
	private static final String TAG = "MainActivity";
	// For saving the current fragment tag
	private static final String CURRENT_FRAGMENT_TAG_KEY = "current_fragment_tag";

	static {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			REQUIRED_PERMISSIONS =
					new String[]{
							android.Manifest.permission.BLUETOOTH_SCAN,
							android.Manifest.permission.BLUETOOTH_ADVERTISE,
							android.Manifest.permission.BLUETOOTH_CONNECT,
							android.Manifest.permission.ACCESS_WIFI_STATE,
							android.Manifest.permission.CHANGE_WIFI_STATE,
							android.Manifest.permission.NEARBY_WIFI_DEVICES,
							android.Manifest.permission.ACCESS_COARSE_LOCATION,
							android.Manifest.permission.ACCESS_FINE_LOCATION,
							android.Manifest.permission.READ_MEDIA_AUDIO,
							android.Manifest.permission.RECORD_AUDIO,
							android.Manifest.permission.VIBRATE,
							android.Manifest.permission.POST_NOTIFICATIONS,
					};
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			REQUIRED_PERMISSIONS =
					new String[]{
							android.Manifest.permission.BLUETOOTH_SCAN,
							android.Manifest.permission.BLUETOOTH_ADVERTISE,
							android.Manifest.permission.BLUETOOTH_CONNECT,
							android.Manifest.permission.ACCESS_WIFI_STATE,
							android.Manifest.permission.CHANGE_WIFI_STATE,
							android.Manifest.permission.ACCESS_COARSE_LOCATION,
							android.Manifest.permission.ACCESS_FINE_LOCATION,
							android.Manifest.permission.READ_EXTERNAL_STORAGE,
							android.Manifest.permission.RECORD_AUDIO,
							android.Manifest.permission.VIBRATE,
					};
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			REQUIRED_PERMISSIONS =
					new String[]{
							android.Manifest.permission.BLUETOOTH,
							android.Manifest.permission.BLUETOOTH_ADMIN,
							android.Manifest.permission.ACCESS_WIFI_STATE,
							android.Manifest.permission.CHANGE_WIFI_STATE,
							android.Manifest.permission.ACCESS_COARSE_LOCATION,
							android.Manifest.permission.ACCESS_FINE_LOCATION,
							android.Manifest.permission.READ_EXTERNAL_STORAGE,
							android.Manifest.permission.RECORD_AUDIO,
							android.Manifest.permission.VIBRATE,
					};
		} else {
			REQUIRED_PERMISSIONS =
					new String[]{
							android.Manifest.permission.BLUETOOTH,
							android.Manifest.permission.BLUETOOTH_ADMIN,
							android.Manifest.permission.ACCESS_WIFI_STATE,
							android.Manifest.permission.CHANGE_WIFI_STATE,
							android.Manifest.permission.ACCESS_COARSE_LOCATION,
							android.Manifest.permission.READ_EXTERNAL_STORAGE,
							android.Manifest.permission.RECORD_AUDIO,
							Manifest.permission.VIBRATE,
					};
		}
	}

	// Launch and treat setting activity
	private ActivityResultLauncher<Intent> settingsLauncher;

	private AppDatabase appDatabase;

	/**
	 * Returns {@code true} if the app was granted all the permissions. Otherwise, returns {@code
	 * false}.
	 */
	public static boolean hasPermissions(Context context, String... permissions) {
		for (String permission : permissions) {
			if (ContextCompat.checkSelfPermission(context, permission)
					!= PackageManager.PERMISSION_GRANTED) {
				return false;
			}
		}
		return true;
	}

	/**
	 * An optional hook to pool any permissions the app needs with the permissions ConnectionsActivity
	 * will request.
	 *
	 * @return All permissions required for the app to properly function.
	 */
	protected String[] getRequiredPermissions() {
		return REQUIRED_PERMISSIONS;
	}

	/**
	 * Called when our Activity has been made visible to the user.
	 */
	@Override
	protected void onStart() {
		super.onStart();
		if (!hasPermissions(this, getRequiredPermissions())) {
			requestPermissions(getRequiredPermissions(), REQUEST_CODE_REQUIRED_PERMISSIONS);
		}
	}

	/**
	 * Called when the user has accepted (or denied) our permission request.
	 */
	@CallSuper
	@Override
	public void onRequestPermissionsResult(
			int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
			int i = 0;
			for (int grantResult : grantResults) {
				if (grantResult == PackageManager.PERMISSION_DENIED) {
					Log.w(TAG, "Failed to request the permission " + permissions[i]);
					runOnUiThread(() -> Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_LONG).show());
					finish();
					return;
				}
				i++;
			}
			recreate();
		}
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
	}

	private SharedPreferences getDefaultSharedPreferences() {
		return getSharedPreferences(Constants.PREFS_FILE_NAME, MODE_PRIVATE);
	}

	private boolean isSetupCompleted() {
		return getDefaultSharedPreferences().getBoolean(Constants.PREF_KEY_IS_SETUP_COMPLETE, false);
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		// Save the tag of the currently active fragment
		Fragment currentFragment = getCurrentFragmentInContainer();
		if (currentFragment != null && currentFragment.getTag() != null) {
			outState.putString(CURRENT_FRAGMENT_TAG_KEY, currentFragment.getTag());
			Log.d(TAG, "Saving state: current fragment tag is " + currentFragment.getTag());
		}
	}

	@Override
	protected void onNewIntent(@NonNull Intent intent) {
		super.onNewIntent(intent);
		handleIncomingIntent(intent);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());

		// Apply user defined settings
		SettingsActivity.applySettings(this);

		// Initialize database on the main thread ui
		appDatabase = AppDatabase.getDB(getApplicationContext());

		// Location service checker
		settingsLauncher = registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> {
					// On setting activity ended
					Log.d(TAG, "Back from location setting settings.");
					checkNearbyApiPreConditions(defaultOnCreate(savedInstanceState));
				}
		);

		// Flag to check if the intent was handled by a specific notification action
		boolean notificationHandled = Boolean.TRUE.equals(SATMeshServiceStatus.getInstance().getServiceReady().getValue())
				&& getIntent() != null;
		if (notificationHandled) {
			notificationHandled = handleIncomingIntent(getIntent());
		}

		// Only run default onCreate logic IF no specific notification intent was handled
		if (!notificationHandled) {
			defaultOnCreate(savedInstanceState);
		}
	}

	private Consumer<Void> defaultOnCreate(Bundle savedInstanceState) {
		final Consumer<Void> showFragment;
		Consumer<Void> showFragmentDefault = (unused) -> {
			// Load host Node
			// Check if setup is complete
			if (isSetupCompleted()) {
				onSetupCompleted();
			} else {
				showWelcomeFragment();
			}
		};
		// Used while activity is recreated
		Consumer<Void> showFragmentRestore = (unused) -> {
			if (isSetupCompleted()) {
				Log.d(TAG, "Restore view in state setup completed!");
				onSetupCompleted(); // Ensure service is running
			}
			// The fragment will reattach itself
		};

		if (savedInstanceState != null) {
			String fragmentTag = savedInstanceState.getString(CURRENT_FRAGMENT_TAG_KEY, null);
			if (fragmentTag != null) {
				showFragment = showFragmentRestore;
			} else {
				showFragment = showFragmentDefault;
			}
		} else {
			showFragment = showFragmentDefault;
		}
		checkNearbyApiPreConditions(showFragment);
		return showFragment;
	}

	/**
	 * Handles incoming Intents, specifically those originating from notifications.
	 * This method is responsible for parsing the Intent and directing navigation
	 * based on the notification type.
	 *
	 * @param intent The Intent received by the MainActivity.
	 * @return true if the Intent was identified as a notification intent and handled, false otherwise.
	 */
	private boolean handleIncomingIntent(@Nullable Intent intent) {
		if (intent == null) {
			Log.d(TAG, "handleIncomingIntent: Intent is null.");
			return false;
		}
		// Checks if the `Intent` is from notification
		if (Constants.ACTION_LAUNCH_FROM_NOTIFICATION.equals(intent.getAction()) &&
				intent.hasExtra(Constants.EXTRA_NOTIFICATION_TYPE)) {
			Log.d(TAG, "Handling intent from notifications.");
			appDatabase.getQueryExecutor().execute(() -> {
				try {
					NotificationType type = NotificationType.valueOf(intent.getStringExtra(Constants.EXTRA_NOTIFICATION_TYPE));
					String addressName, displayName;
					Node remoteNode;
					switch (type) {
						case NEW_MESSAGE:
							String senderAddress = intent.getStringExtra(Constants.MESSAGE_SENDER_ADDRESS);
							long messageId = intent.getLongExtra(Constants.MESSAGE_ID, -1L);
							if (senderAddress == null) {
								throw new NullPointerException("Missing sender address for NEW_MESSAGE notification.");
							}
							remoteNode = appDatabase.nodeDao().getNodeByAddressNameSync(senderAddress);
							if (remoteNode == null) {
								throw new NullPointerException("Unable to locate the remote node bound to the message with address: " + senderAddress);
							}
							runOnUiThread(() -> discussWith(remoteNode, false, messageId != -1L ? messageId : null));
							break;
						case NEW_NODE_DISCOVERED:
							boolean isNew = intent.getBooleanExtra(Constants.NODE_IS_NEW, false);
							addressName = intent.getStringExtra(Constants.NODE_ADDRESS);
							if (addressName == null) {
								throw new NullPointerException("Missing node address for NEW_NODE_DISCOVERED notification.");
							}
							if (isNew) {
								// For new discovery, redirect to node discovery fragment, this is in favor of personal data exchange
								runOnUiThread(() -> moveToDiscoveryView(false));
							} else {
								// For re-discovery, move to chat fragment
								Node node = appDatabase.nodeDao().getNodeByAddressNameSync(addressName);
								if (node == null) {
									throw new NullPointerException("Undesirable behavior: the re-discovered node (address=" + addressName + ") is not found in DB.");
								}
								runOnUiThread(() -> discussWith(node, false, null));
							}
							break;
						case ROUTE_DISCOVERY_INITIATED:
							/*
							 * We would add a new fragment to display the route discovery status.
							 * Waiting that, we just execute the default behavior
							 */
							Log.d(TAG, "Handle ROUTE_DISCOVERY_INITIATED notification click for address: "
									+ intent.getStringExtra(Constants.NODE_ADDRESS));
							runOnUiThread(this::navigateToMainScreen);
							break;
						case ROUTE_DISCOVERY_RESULT:
							boolean found = intent.getBooleanExtra(Constants.ROUTE_IS_FOUND, false);
							addressName = intent.getStringExtra(Constants.NODE_ADDRESS);
							displayName = intent.getStringExtra(Constants.NODE_DISPLAY_NAME);
							if (addressName == null) {
								throw new NullPointerException("Missing node address for ROUTE_DISCOVERY_RESULT notification.");
							}
							if (!found) {
								Log.d(TAG, "Handle ROUTE_DISCOVERY_RESULT=false, for address=" + addressName);
								String toastText = getString(R.string.notification_content_route_not_found, displayName != null ? displayName : addressName);
								runOnUiThread(() -> {
									navigateToMainScreen();
									Toast.makeText(this, toastText, Toast.LENGTH_LONG).show();
								});
							} else {
								// Else, open the discussion
								remoteNode = appDatabase.nodeDao().getNodeByAddressNameSync(addressName);
								if (remoteNode == null) {
									throw new NullPointerException("Undesirable behavior: the remote node is not found in DB, ROUTE_DISCOVERY_RESULT=true.");
								}
								runOnUiThread(() -> discussWith(remoteNode, false, null));
							}
							break;
					}
				} catch (NullPointerException | IllegalArgumentException e) {
					Log.w(TAG, "Unable to parse the notification's intent.", e);
					runOnUiThread(this::navigateToMainScreen);
				} catch (Exception e) {
					Log.e(TAG, "Undesired behavior while parse the notification intent.", e);
					runOnUiThread(this::navigateToMainScreen);
				}
			});
			// Process notification dismissing
			Intent serviceIntent = new Intent(this, SATMeshCommunicationService.class);
			serviceIntent.putExtras(intent); // Transfer required data from the current intent to service intent
			serviceIntent.putExtra(Constants.NOTIFICATION_ID, intent.getIntExtra(Constants.NOTIFICATION_GROUP_ID, -1)); // Make sure it is the notification group that is canceled
			serviceIntent.setAction(Constants.ACTION_NOTIFICATION_DISMISSED);
			startService(serviceIntent);
			return true;
		}
		return false;
	}

	/**
	 * Show the WelcomeFragment for the initial configuration.
	 */
	private void showWelcomeFragment() {
		navigateTo(WelcomeFragment.newInstance(), Constants.TAG_WELCOME_FRAGMENT, false);
	}

	// Implementation of `WelcomeFragment.OnWelcomeCompleteListener`
	@Override
	public void onWelcomeCompleted(@NonNull String username) {
		Log.d(TAG, "Welcome completed with username: " + username);
		// Execute in background
		appDatabase.getQueryExecutor().execute(() -> {
			try {
				String addressName = Constants.NODE_ADDRESS_NAME_PREFIX + java.util.UUID.randomUUID().toString();

				// Create the Node
				Node hostNode = new Node();
				hostNode.setAddressName(addressName);
				hostNode.setDisplayName(username);

				// Save node in DB
				long nodeId = appDatabase.nodeDao().insert(hostNode);
				if (nodeId != -1) {
					hostNode.setId(nodeId); // apply the ID

					// Save host node data in the SharedPreferences
					getDefaultSharedPreferences().edit()
							.putBoolean(Constants.PREF_KEY_IS_SETUP_COMPLETE, true)
							.putLong(Constants.PREF_KEY_HOST_NODE_ID, nodeId)
							.putString(Constants.PREF_KEY_HOST_ADDRESS_NAME, addressName)
							.apply();

					Log.d(TAG, "Host node created and saved: " + hostNode.getDisplayName() + " (" + hostNode.getAddressName() + ") with ID: " + hostNode.getId());

					// Navigate to ChatListFragment or NearbyDiscoveryFragment on UI thread
					runOnUiThread(this::onSetupCompleted);
				} else {
					Log.e(TAG, "Failed to insert host node into database.");
					runOnUiThread(() -> Toast.makeText(this, R.string.node_profile_saving_failed, Toast.LENGTH_LONG).show());
					// Stay on the fragment, user will retry
				}
			} catch (Exception e) {
				Log.e(TAG, "Error during host node setup: " + e.getMessage(), e);
				runOnUiThread(() -> Toast.makeText(this, R.string.internal_error, Toast.LENGTH_LONG).show());
			}
		});
	}

	@Override
	public void discussWith(@NonNull Node remoteNode, boolean removePreviousFragment, @Nullable Long messageIdToScrollTo) {
		// Refresh from db
		appDatabase.getQueryExecutor().execute(() -> {
			Node hostNode = appDatabase.nodeDao().getNodeByIdSync(
					getDefaultSharedPreferences()
							.getLong(Constants.PREF_KEY_HOST_NODE_ID, -1L)
			);
			if (hostNode == null) {
				Log.e(TAG, "Unable to find the host node");
				finish();
				return;
			}
			Bundle bundle;
			if (messageIdToScrollTo != null) {
				bundle = new Bundle();
				bundle.putLong(ChatFragment.MESSAGE_ID_TO_SCROLL_KEY, messageIdToScrollTo);
			} else {
				bundle = null;
			}
			Consumer<Node> goToChat = node -> navigateTo(ChatFragment.newInstance(hostNode, node, bundle), Constants.TAG_CHAT_FRAGMENT, removePreviousFragment);
			Node node = appDatabase.nodeDao().getNodeByAddressNameSync(remoteNode.getAddressName());
			if (node == null) {
				try {
					long id = appDatabase.nodeDao().insert(remoteNode);
					remoteNode.setId(id);
					goToChat.accept(remoteNode);
				} catch (Exception e) {
					Log.d(TAG, "discussWith() : node insertion failed, address=" + remoteNode.getAddressName(), e);
				}
			} else {
				goToChat.accept(node);
			}
		});
	}

	// implementation of NearbyDiscoveryListener
	public void moveToDiscoveryView(boolean removeLast) {
		String addressName = getDefaultSharedPreferences().getString(Constants.PREF_KEY_HOST_ADDRESS_NAME, null);
		navigateTo(NearbyDiscoveryFragment.newInstance(Objects.requireNonNull(addressName)), Constants.TAG_DISCOVERY_FRAGMENT, removeLast);
	}

	// Implementation of `ChatListAccessor`
	public void moveToChatList(boolean removeLast) {
		resetToHomeScreen();
		Long hostNodeId = getDefaultSharedPreferences().getLong(Constants.PREF_KEY_HOST_NODE_ID, -1L);
		navigateTo(ChatListFragment.newInstance(hostNodeId), Constants.TAG_CHAT_LIST_FRAGMENT, removeLast);
	}

	// Implementation of `AppHomeListener`
	@Override
	public void backToHome() {
		resetToHomeScreen();
		navigateToMainScreen();
	}

	// Implementation of `DiscussionMenuListener`
	public void moveToSearchFragment(Long hostNodeId) {
		if (hostNodeId == null) {
			return;
		}
		navigateTo(SearchFragment.newInstance(hostNodeId), SearchFragment.TAG, true);
	}

	public void moveToKnownNodesFragment(Long hostNodeId) {
		if (hostNodeId == null) {
			return;
		}
		navigateTo(KnownNodesFragment.newInstance(hostNodeId), KnownNodesFragment.TAG, true);
	}

	private void onSetupCompleted() {
		startCommunicationService();
		navigateTo(LoadingFragment.newInstance(), LoadingFragment.TAG, false);
	}

	// Implementation of `ServiceLoadingListener`
	@Override
	public void onServicesReady() {
		// Remove the loading fragment from the view
		Fragment loading = getCurrentFragmentInContainer();
		FragmentManager fragmentManager = getSupportFragmentManager();
		if (loading != null) {
			fragmentManager.beginTransaction().remove(loading).commit();
			if (loading instanceof LoadingFragment) {
				Log.d(TAG, "Success remove of LoadingFragment");
			}
		} else {
			Log.w(TAG, "The fragment LoadingFragment is not found onServicesReady.");
		}
		navigateToMainScreen();
	}

	/**
	 * Show the fragment to display after configuration or node loading.
	 */
	private void navigateToMainScreen() {
		// The host node is identified, init app service
		appDatabase.getQueryExecutor().execute(() -> {
			long count = appDatabase.messageDao().countAll();
			if (count > 0L) {
				// There is at least one message, navigate to ChatListFragment
				Log.i(TAG, "Ready to display chat list");
				moveToChatList(false);
			} else {
				// There is no message, redirect user on discovery fragment
				moveToDiscoveryView(true);
			}
		});
	}

	/**
	 * Starts the SATMeshCommunicationService as a foreground service.
	 * This method also sends an intent to initialize communication modules if setup is complete.
	 */
	private void startCommunicationService() {
		Log.d(TAG, "Attempting to start SATMeshCommunicationService.");
		Intent serviceIntent = new Intent(this, SATMeshCommunicationService.class);
		serviceIntent.setAction(SATMeshCommunicationService.ACTION_START_FOREGROUND_SERVICE);

		// Start the service appropriately based on Android version
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			ContextCompat.startForegroundService(this, serviceIntent);
		} else {
			startService(serviceIntent);
		}

		// After starting the service, if setup is complete, tell it to initialize modules.
		// This covers cases where service was already running (e.g., from boot) but not initialized.
		if (isSetupCompleted()) {
			Log.d(TAG, "Host setup is complete. Sending initialize command to service.");
			Intent initializeIntent = new Intent(this, SATMeshCommunicationService.class);
			initializeIntent.setAction(SATMeshCommunicationService.ACTION_INITIALIZE_COMMUNICATION_MODULES);
			startService(initializeIntent); // Use startService for subsequent commands to a running service
		} else {
			Log.d(TAG, "Host setup not completed yet. Service will wait for explicit initialization.");
		}
	}

	/**
	 * Reset the fragment container to empty
	 */
	private void resetToHomeScreen() {
		FragmentManager fragmentManager = getSupportFragmentManager();
		if (fragmentManager.getBackStackEntryCount() > 0) {
			fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
		}
	}

	public Fragment getCurrentFragmentInContainer() {
		return getSupportFragmentManager().findFragmentById(R.id.fragment_container);
	}

	/**
	 * Navigates to a new fragment, replacing the current one in the designated container.
	 * This method provides fine-grained control over back stack behavior and explicit fragment removal.
	 *
	 * @param fragment    The new Fragment to display.
	 * @param fragmentTag An optional tag for the new fragment, useful for later retrieval.
	 * @param removeLast  If set to true, the currently displayed fragment in the container
	 *                    will be explicitly removed and its entry popped from the back stack
	 *                    before the new fragment is added. This ensures the previous fragment
	 *                    is fully destroyed and not simply replaced.
	 */
	public void navigateTo(Fragment fragment, String fragmentTag, boolean removeLast) {
		checkNearbyApiPreConditions((unused) -> {
			// Navigate only if the pre-conditions are verified.
			FragmentManager fragmentManager = getSupportFragmentManager();
			FragmentTransaction transaction = fragmentManager.beginTransaction();

			/*
			 * Determine if the current fragment needs to be explicitly removed and its back stack entry popped.
			 * This is useful for scenarios where you want to ensure the previous fragment is fully
			 * destroyed and its state is not retained in the back stack.
			 */
			Fragment currentToRemove = removeLast ? getCurrentFragmentInContainer() : null;

			if (currentToRemove != null) {
				// Explicitly remove the specified fragment
				transaction.remove(currentToRemove).commit(); // Commit this removal operation immediately

				// Pop the last transaction from the back stack to prevent the removed fragment from reappearing
				fragmentManager.popBackStack();

				// Start a new transaction for the subsequent operations, as the previous one was committed
				transaction = fragmentManager.beginTransaction();
			}

			// Replace the fragment in the container with the new fragment
			transaction.replace(R.id.fragment_container, fragment, fragmentTag);

			// Add the transaction to the back stack if requested
			transaction.addToBackStack(null); // 'null' means no specific name for this back stack entry

			// Allow the FragmentManager to reorder operations for performance and visual consistency
			transaction.setReorderingAllowed(true);

			// Commit the final set of operations (replace, and optionally addToBackStack)
			transaction.commit();
		});
	}

	private boolean isLocationServicesEnabled() {
		LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		if (locationManager == null) {
			Log.e(TAG, "Unable to get location manager !");
			return false;
		}
		// Test if GPS or internet provider is enabled
		return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
				locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
	}

	private void checkNearbyApiPreConditions(@NonNull Consumer<Void> onSuccess) {
		if (!isLocationServicesEnabled()) {
			new AlertDialog.Builder(this)
					.setTitle(R.string.nearby_requirement_dialog_title)
					.setMessage(R.string.nearby_requirement_dialog_message)
					.setPositiveButton(R.string.positive_button_activate, (dialog, which) -> {
						// Open system location settings
						Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
						settingsLauncher.launch(intent);
					})
					.setNegativeButton(R.string.negative_button_cancel, (dialog, which) -> {
						Log.w(TAG, "Location services not enabled, discovery cannot start.");
						finish();
					})
					.show();
		} else {
			// OK, now we can start
			onSuccess.accept(null);
		}
	}
}