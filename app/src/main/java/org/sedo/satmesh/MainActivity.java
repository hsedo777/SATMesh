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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.sedo.satmesh.databinding.ActivityMainBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.signal.SignalManager.SignalInitializationCallback;
import org.sedo.satmesh.ui.ChatFragment;
import org.sedo.satmesh.ui.NearbyDiscoveryFragment;
import org.sedo.satmesh.ui.WelcomeFragment;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.utils.Constants;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity implements WelcomeFragment.OnWelcomeCompletedListener, NearbyDiscoveryFragment.DiscoveryFragmentListener {

	/**
	 * These permissions are required before connecting to Nearby Connections.
	 */
	private static final String[] REQUIRED_PERMISSIONS;
	private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;
	private static final String TAG = "MainActivity";

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

	private SharedPreferences sharedPreferences;
	private AppDatabase appDatabase;
	private SignalManager signalManager;
	private ExecutorService executor;
	private Node hostNode;

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

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());

		// Initialize the SharedPreferences
		sharedPreferences = getSharedPreferences(Constants.PREFS_FILE_NAME, MODE_PRIVATE);

		// Initialize database on the main thread ui
		appDatabase = AppDatabase.getDB(getApplicationContext());

		// Get instance of `SignalManager`
		signalManager = SignalManager.getInstance(getApplicationContext());

		// Background executor
		executor = Executors.newSingleThreadExecutor();

		final Consumer<Void> showFragment = (unused) -> {
			// Load host Node
			if (isHostNodeSetupComplete()) {
				loadHostNodeFromDatabase();
			} else {
				showWelcomeFragment();
			}
		};
		// Location service checker
		settingsLauncher = registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> {
					// On setting activity ended
					Log.d(TAG, "Back from location setting settings.");
					checkNearbyApiPreConditions(showFragment);
				}
		);

		checkNearbyApiPreConditions(showFragment);
	}

	/**
	 * Checks if host {@link Node} data is stored in the SharedPreferences.
	 */
	private boolean isHostNodeSetupComplete() {
		return sharedPreferences.getBoolean(Constants.PREF_KEY_IS_SETUP_COMPLETE, false);
	}

	/**
	 * Show the WelcomeFragment for the initial configuration.
	 */
	private void showWelcomeFragment() {
		navigateTo(WelcomeFragment.newInstance(), Constants.TAG_WELCOME_FRAGMENT, false, false);
	}

	// Caller might call this method in background
	private void initializeSignalManager() {
		signalManager.initialize(new SignalInitializationCallback() {
			@Override
			public void onSuccess() {
				runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.signal_key_init_success, Toast.LENGTH_LONG).show());
			}

			@Override
			public void onError(Exception e) {
				runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.signal_key_init_failed, Toast.LENGTH_LONG).show());
			}
		});
	}

	/**
	 * In background, load host Node data from the database.
	 */
	private void loadHostNodeFromDatabase() {
		executor.execute(() -> {
			long hostNodeId = sharedPreferences.getLong(Constants.PREF_KEY_HOST_NODE_ID, -1L);
			String hostAddressName = sharedPreferences.getString(Constants.PREF_KEY_HOST_ADDRESS_NAME, null);

			if (hostNodeId != -1L && hostAddressName != null) {
				hostNode = appDatabase.nodeDao().getNodeById(hostNodeId);
				if (hostNode != null) {
					Log.d(TAG, "Host node loaded: " + hostNode.getDisplayName() + " (" + hostNode.getAddressName() + ")");
					initializeSignalManager();
					runOnUiThread(() -> showWelcomeFragmentFollower(false));
				} else {
					Log.e(TAG, "Host node not found in DB despite SharedPreferences entry. Forcing re-setup.");
					runOnUiThread(this::showWelcomeFragment);
				}
			} else {
				Log.e(TAG, "SharedPreferences corrupt or incomplete for host node. Forcing re-setup.");
				runOnUiThread(this::showWelcomeFragment);
			}
		});
	}

	// Implementation of `WelcomeFragment.OnWelcomeCompleteListener`
	@Override
	public void onWelcomeCompleted(String username) {
		Log.d(TAG, "Welcome completed with username: " + username);
		// Execute in background
		executor.execute(() -> {
			try {
				String addressName = Constants.NODE_ADDRESS_NAME_PREFIX + java.util.UUID.randomUUID().toString();
				initializeSignalManager();

				// Create the Node
				Node hostNode = new Node();
				hostNode.setAddressName(addressName);
				hostNode.setDisplayName(username);

				// Save node in DB
				long nodeId = appDatabase.nodeDao().insert(hostNode);
				if (nodeId != -1) {
					hostNode.setId(nodeId); // apply the ID

					// Save host node data in the SharedPreferences
					sharedPreferences.edit()
							.putBoolean(Constants.PREF_KEY_IS_SETUP_COMPLETE, true)
							.putLong(Constants.PREF_KEY_HOST_NODE_ID, nodeId)
							.putString(Constants.PREF_KEY_HOST_ADDRESS_NAME, addressName)
							.apply();

					this.hostNode = hostNode;

					Log.d(TAG, "Host node created and saved: " + hostNode.getDisplayName() + " (" + hostNode.getAddressName() + ") with ID: " + hostNode.getId());

					// Navigate to ChatListFragment or NearbyDiscoveryFragment on UI thread
					runOnUiThread(() -> showWelcomeFragmentFollower(false));
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

	// Implementation of `NearbyDiscoveryFragment.DiscoveryFragmentListener`


	@Override
	public void discussWith(@NonNull Node remoteNode) {
		// Refresh from db
		executor.execute(() -> {
			Consumer<Node> goToChat = node -> navigateTo(ChatFragment.newInstance(hostNode, node), Constants.TAG_CHAT_FRAGMENT, false, true);
			NodeRepository repository = new NodeRepository(getApplicationContext());
			Node node = repository.findNode(remoteNode.getAddressName());
			if (node == null) {
				repository.insertNode(remoteNode, success -> {
					if (success) {
						goToChat.accept(remoteNode);
					} else {
						Log.d(TAG, "discussWith() : node insertion failed, address=" + remoteNode.getAddressName());
					}
				});
			} else {
				goToChat.accept(node);
			}
		});
	}

	/**
	 * Show the fragment to display after configuration or node loading.
	 */
	private void showWelcomeFragmentFollower(boolean addToBackStack) {
		// The host node is identified. Get NearbyManager instance
		executor.execute(() -> {
			// Init NearbyManager
			NearbyManager.getInstance(getApplicationContext(), hostNode.getAddressName());
			long count = appDatabase.messageDao().countAll();
			if (count > 0L) {
				// There is at least one message, navigate to ChatListFragment
				Log.i(TAG, "Ready to display chat list");
				// ChatListFragment.newInstance(), Constants.TAG_CHAT_LIST_FRAGMENT
				navigateTo(NearbyDiscoveryFragment.newInstance(hostNode.getAddressName(), addToBackStack), Constants.TAG_DISCOVERY_FRAGMENT, true, addToBackStack);
			} else {
				// There is no message, redirect user on discovery fragment
				navigateTo(NearbyDiscoveryFragment.newInstance(hostNode.getAddressName(), addToBackStack), Constants.TAG_DISCOVERY_FRAGMENT, true, addToBackStack);
			}
		});
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (executor != null) {
			executor.shutdown();
		}
		//NearbyManager.getInstance(getApplicationContext(), hostNode.getAddressName()).stopNearby();
	}

	public Fragment getCurrentFragmentInContainer() {
		return getSupportFragmentManager().findFragmentById(R.id.fragment_container);
	}

	/**
	 * Navigates to a new fragment, replacing the current one in the designated container.
	 * This method provides fine-grained control over back stack behavior and explicit fragment removal.
	 *
	 * @param fragment       The new Fragment to display.
	 * @param fragmentTag    An optional tag for the new fragment, useful for later retrieval.
	 * @param removeLast     If set to true, the currently displayed fragment in the container
	 *                       will be explicitly removed and its entry popped from the back stack
	 *                       before the new fragment is added. This ensures the previous fragment
	 *                       is fully destroyed and not simply replaced.
	 * @param addToBackStack If true, the transaction to display the new fragment will be added
	 *                       to the back stack, allowing the user to navigate back to the
	 *                       previous state by pressing the back button.
	 */
	public void navigateTo(Fragment fragment, String fragmentTag, boolean removeLast, boolean addToBackStack) {
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
		if (addToBackStack) {
			transaction.addToBackStack(null); // 'null' means no specific name for this back stack entry
		}

		// Allow the FragmentManager to reorder operations for performance and visual consistency
		transaction.setReorderingAllowed(true);

		// Commit the final set of operations (replace, and optionally addToBackStack)
		transaction.commit();
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

	private void checkNearbyApiPreConditions(Consumer<Void> onSuccess) {
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