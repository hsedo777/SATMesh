package org.sedo.satmesh.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.sedo.satmesh.R;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

	private final ExecutorService executorService = Executors.newSingleThreadExecutor();

	@Override
	public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
		setPreferencesFromResource(R.xml.preferences, rootKey);

		setupUsernamePreference();
		setupNodeIdPreference();
	}

	@Override
	public void onResume() {
		super.onResume();
		Objects.requireNonNull(getPreferenceScreen().getSharedPreferences())
				.registerOnSharedPreferenceChangeListener(this);
		updateNodeIdSummary();
	}

	@Override
	public void onPause() {
		super.onPause();
		Objects.requireNonNull(getPreferenceScreen().getSharedPreferences())
				.unregisterOnSharedPreferenceChangeListener(this);
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
		if (key == null) {
			return;
		}
		if (key.equals(getString(R.string.pref_key_theme))) {
			if (getActivity() != null) {
				getActivity().recreate();
			}
		} else if (key.equals(getString(R.string.pref_key_username))) {
			String newUsername = sharedPreferences.getString(key, null);
			updateUsernameInRepository(newUsername);
		}
		// No action for `key.equals(getString(R.string.pref_key_font_size)` now.
	}

	private void setupUsernamePreference() {
		EditTextPreference preference = findPreference(getString(R.string.pref_key_username));
		if (preference != null) {
			preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_TEXT));
			preference.setSummaryProvider(new Preference.SummaryProvider<EditTextPreference>() {
				@Nullable
				@Override
				public CharSequence provideSummary(@NonNull EditTextPreference etp) {
					String text = etp.getText();
					return (text == null || text.isEmpty()) ? null : text;
				}
			});
		}
	}

	private void setupNodeIdPreference() {
		Preference preference = findPreference(getString(R.string.pref_key_node_id));
		if (preference != null) {
			updateNodeIdSummary();
			preference.setOnPreferenceClickListener(pref -> {
				CharSequence summary = pref.getSummary();
				if (summary != null) {
					copyNodeIdToClipboard(summary.toString());
				}
				return true;
			});
		}
	}

	private void updateNodeIdSummary() {
		Preference preference = findPreference(getString(R.string.pref_key_node_id));
		if (preference != null) {
			executorService.execute(() -> {
				String nodeId = ""; //NodeRepository.getInstance(requireContext()).getNodeId();
				// TODO
				if (getActivity() != null) {
					getActivity().runOnUiThread(() -> preference.setSummary(nodeId));
				}
			});
		}
	}

	private void updateUsernameInRepository(@Nullable String newUsername) {
		if (newUsername == null) {// TODO : add more control
			return;
		}
		executorService.execute(() -> {
			// NodeRepository repository = new NodeRepository(requireContext());
			// TODO: implement setting nam up to date

			if (getActivity() != null) {
				getActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "Username updated to: " + newUsername, Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void copyNodeIdToClipboard(@NonNull String nodeId) {
		android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
				requireActivity().getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			android.content.ClipData clip = android.content.ClipData.newPlainText("Node ID", nodeId);
			clipboard.setPrimaryClip(clip);
			Toast.makeText(requireContext(), R.string.node_id_copied_to_clipboard, Toast.LENGTH_SHORT).show();
		}
	}
}