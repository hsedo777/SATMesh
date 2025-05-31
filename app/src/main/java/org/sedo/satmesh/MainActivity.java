package org.sedo.satmesh;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.sedo.satmesh.databinding.ActivityMainBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.signal.SignalManager.SignalInitializationCallback;
import org.sedo.satmesh.ui.WelcomeFragment;
import org.sedo.satmesh.utils.Constants;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WelcomeFragment.OnWelcomeCompletedListener {

	private static final String TAG = "MainActivity";

	private SharedPreferences sharedPreferences;
	private AppDatabase appDatabase;
	private SignalManager signalManager;
	private ExecutorService databaseExecutor;
	private Node hostNode;

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
		// TODO
		// getSupportFragmentManager().beginTransaction()
				//.replace(R.id.fragment_container, ChatListFragment.newInstance(), Constants.TAG_CHAT_LIST_FRAGMENT)
		//		.commit();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (databaseExecutor != null) {
			databaseExecutor.shutdown();
		}
	}
}