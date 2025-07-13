package org.sedo.satmesh;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.sedo.satmesh.ui.SettingsFragment;

public class SettingsActivity extends AppCompatActivity {

	private static final String EXTRA_LOCAL_NODE_UUID = "local_node_uuid_extra";

	private String localNodeUuid;

	public static Intent newIntent(@NonNull Context context, @NonNull String localNodeUuid) {
		Intent intent = new Intent(context, SettingsActivity.class);
		intent.putExtra(EXTRA_LOCAL_NODE_UUID, localNodeUuid);
		return intent;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_settings);

		if (savedInstanceState != null) {
			localNodeUuid = savedInstanceState.getString(EXTRA_LOCAL_NODE_UUID);
		} else {
			localNodeUuid = getIntent().getStringExtra(EXTRA_LOCAL_NODE_UUID);
		}
		if (localNodeUuid == null) {
			// Very bad
			finish();
			return;
		}

		// Navigate only on initial creation
		if (savedInstanceState == null) {
			getSupportFragmentManager()
					.beginTransaction()
					.replace(R.id.settings_container, SettingsFragment.newInstance(localNodeUuid))
					.commit();
		}
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setTitle(R.string.title_activity_settings);
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString(EXTRA_LOCAL_NODE_UUID, localNodeUuid);
	}

	@Override
	public boolean onSupportNavigateUp() {
		Intent intent = new Intent(this, MainActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);

		finish();
		return true;
	}
}