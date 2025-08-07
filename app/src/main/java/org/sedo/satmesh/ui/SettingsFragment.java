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
import androidx.preference.PreferenceManager;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.utils.Utils;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

	public static final int DEFAULT_THEME_INDEX = 2;
	private static final String ARG_LOCAL_NODE_UUID = "local_node_uuid";
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();

	private NodeRepository nodeRepository;
	private Node hostNode;
	private String localNodeUuid;
	private UserDisplayNameListener displayNameListener;

	public static SettingsFragment newInstance(String localNodeUuid) {
		SettingsFragment fragment = new SettingsFragment();
		Bundle args = new Bundle();
		args.putString(ARG_LOCAL_NODE_UUID, localNodeUuid);
		fragment.setArguments(args);
		return fragment;
	}

	public static @NonNull String[] getThemeArray(@NonNull Context context) {
		return context.getResources().getStringArray(R.array.pref_theme_values);
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof UserDisplayNameListener) {
			displayNameListener = (UserDisplayNameListener) context;
		} else {
			throw new RuntimeException("The activity must implement `UserDisplayNameListener`");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		displayNameListener = null;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		nodeRepository = new NodeRepository(requireContext());
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString(ARG_LOCAL_NODE_UUID, localNodeUuid);
	}

	@Override
	public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
		setPreferencesFromResource(R.xml.preferences, rootKey);

		if (getArguments() != null) {
			localNodeUuid = getArguments().getString(ARG_LOCAL_NODE_UUID);
		} else if (savedInstanceState != null) {
			localNodeUuid = savedInstanceState.getString(ARG_LOCAL_NODE_UUID);
		}

		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
		setupThemePreference(sharedPrefs);
		setupUsernamePreference();
		setupNodeIdPreference();
		loadHostNodeAndRefreshUI();
	}

	@Override
	public void onResume() {
		super.onResume();
		Objects.requireNonNull(getPreferenceScreen().getSharedPreferences())
				.registerOnSharedPreferenceChangeListener(this);
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

	private void setupThemePreference(SharedPreferences sharedPrefs) {
		Preference preference = findPreference(getString(R.string.pref_key_theme));
		if (preference != null) {
			String[] themes = getThemeArray(requireContext());
			String themeValue = sharedPrefs.getString(getString(R.string.pref_key_theme), themes[DEFAULT_THEME_INDEX]);
			// Convert theme value to its locale sensitive value
			String[] localeThemes = getResources().getStringArray(R.array.pref_theme_entries);
			int index = Arrays.asList(themes).indexOf(themeValue);
			preference.setSummary(localeThemes[index]);
		}
	}

	private void setupUsernamePreference() {
		EditTextPreference preference = findPreference(getString(R.string.pref_key_username));
		if (preference != null) {
			preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_TEXT));
			preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) etp -> {
				String text = etp.getText();
				return (text == null || text.isEmpty()) ? null : text;
			});
			if (hostNode != null && hostNode.getDisplayName() != null) {
				preference.setText(hostNode.getDisplayName());
			}
		}
	}

	private void setupNodeIdPreference() {
		Preference preference = findPreference(getString(R.string.pref_key_node_id));
		if (preference != null) {
			preference.setSummary(localNodeUuid);
			preference.setOnPreferenceClickListener(pref -> {
				CharSequence summary = pref.getSummary();
				if (summary != null) {
					copyNodeIdToClipboard(summary.toString());
				}
				return true;
			});
		}
	}

	private void loadHostNodeAndRefreshUI() {
		if (localNodeUuid == null) {
			return;
		}

		executorService.execute(() -> {
			hostNode = nodeRepository.findNodeSync(localNodeUuid);

			if (hostNode != null) {
				refreshPreferenceSummaries();
			}
			//else: Undesired behavior
		});
	}

	private void refreshPreferenceSummaries() {
		if (getActivity() != null) {
			getActivity().runOnUiThread(() -> {
				EditTextPreference usernamePreference = findPreference(getString(R.string.pref_key_username));
				if (usernamePreference != null && hostNode != null && hostNode.getDisplayName() != null) {
					usernamePreference.setText(hostNode.getDisplayName());
				}

				Preference nodeIdPreference = findPreference(getString(R.string.pref_key_node_id));
				if (nodeIdPreference != null && hostNode != null && hostNode.getAddressName() != null) {
					nodeIdPreference.setSummary(hostNode.getAddressName());
				} else if (nodeIdPreference != null) {
					nodeIdPreference.setSummary(localNodeUuid);
				}
			});
		}
	}

	private void updateUsernameInRepository(@Nullable String newUsername) {
		boolean empty = newUsername == null || newUsername.trim().isEmpty();
		if (!Utils.isUsernameValid(newUsername)) {
			Toast.makeText(requireContext(),
					empty ? R.string.username_cannot_be_empty : R.string.username_invalid
					, Toast.LENGTH_SHORT).show();
			EditTextPreference usernamePref = findPreference(getString(R.string.pref_key_username));
			if (usernamePref != null && hostNode != null) {
				requireActivity().runOnUiThread(() -> usernamePref.setText(hostNode.getDisplayName()));
			}
			return;
		}

		executorService.execute(() -> {
			if (hostNode != null) {
				String oldUsername = hostNode.getDisplayName();
				String newUsernameTrimmed = Objects.requireNonNull(newUsername).trim();
				hostNode.setDisplayName(newUsernameTrimmed);
				nodeRepository.update(hostNode, success -> {
					if (isAdded()) {
						if (success) {
							requireActivity().runOnUiThread(() -> {
								Toast.makeText(requireContext(), getString(R.string.username_updated_to, hostNode.getDisplayName()), Toast.LENGTH_SHORT).show();
								if (displayNameListener != null) {
									displayNameListener.onUserDisplayNameChanged(oldUsername, newUsernameTrimmed);
								}
							});
						} else {
							// Revert the change in memory if the DB update fails.
							hostNode.setDisplayName(oldUsername);
							requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), R.string.username_update_failed, Toast.LENGTH_SHORT).show());
						}
					}
				});
			} else {
				requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), R.string.username_update_failed_no_node, Toast.LENGTH_SHORT).show());
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