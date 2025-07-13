package org.sedo.satmesh;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

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

		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		String[] themes = getResources().getStringArray(R.array.pref_theme_values);
		String themeValue = sharedPrefs.getString(getString(R.string.pref_key_theme), themes[2]);
		switch (themeValue) {
			case "dark":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
				break;
			case "light":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
				break;
			default:
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
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
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				onSupportNavigateUp();
			}
		});
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