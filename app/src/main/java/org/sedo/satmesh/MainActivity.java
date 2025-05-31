package org.sedo.satmesh;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.sedo.satmesh.databinding.ActivityMainBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.signal.SignalManager.SignalInitializationCallback;
import org.sedo.satmesh.ui.WelcomeFragment;
import org.sedo.satmesh.utils.Constants;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WelcomeFragment.OnWelcomeCompletedListener {

	/**
	 * These permissions are required before connecting to Nearby Connections.
	 */
	private static final String[] REQUIRED_PERMISSIONS;

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
							android.Manifest.permission.READ_MEDIA_IMAGES,
							android.Manifest.permission.READ_MEDIA_AUDIO,
							android.Manifest.permission.READ_MEDIA_VIDEO,
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

	private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;

	private static final String TAG = "MainActivity";

	private SharedPreferences sharedPreferences;
	private AppDatabase appDatabase;
	private SignalManager signalManager;
	private ExecutorService databaseExecutor;
	private Node hostNode;

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
		signalManager = new SignalManager(getApplicationContext());

		// Background executor
		databaseExecutor = Executors.newSingleThreadExecutor();

		// Load host Node
		if (isHostNodeSetupComplete()) {
			Log.d(TAG, "Host node setup is complete. Loading host node from DB.");
			loadHostNodeFromDatabase();
		} else {
			Log.d(TAG, "Host node setup not complete. Showing WelcomeFragment.");
			showWelcomeFragment();
		}
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
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragment_container, WelcomeFragment.newInstance(), Constants.TAG_WELCOME_FRAGMENT)
				.commit();
	}

	// Caller might call this method in background
	private void initializeSignalManager(){
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
		databaseExecutor.execute(() -> {
			long hostNodeId = sharedPreferences.getLong(Constants.PREF_KEY_HOST_NODE_ID, -1L);
			String hostAddressName = sharedPreferences.getString(Constants.PREF_KEY_HOST_ADDRESS_NAME, null);

			if (hostNodeId != -1L && hostAddressName != null) {
				hostNode = appDatabase.nodeDao().getNodeById(hostNodeId);
				if (hostNode != null) {
					Log.d(TAG, "Host node loaded: " + hostNode.getDisplayName() + " (" + hostNode.getAddressName() + ")");
					initializeSignalManager();
					runOnUiThread(this::showChatListFragment);
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

	@Override
	public void onWelcomeCompleted(String username) {
		Log.d(TAG, "Welcome completed with username: " + username);
		// Execute in background
		databaseExecutor.execute(() -> {
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

					// Navigate to ChatListFragment on UI thread
					runOnUiThread(this::showChatListFragment);
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

	/**
	 * Show the ChatListFragment after configuration or node loading.
	 */
	private void showChatListFragment() {
		/* TODO
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragment_container, ChatListFragment.newInstance(), Constants.TAG_CHAT_LIST_FRAGMENT)
				.commit();*/
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (databaseExecutor != null) {
			databaseExecutor.shutdown();
		}
	}
}