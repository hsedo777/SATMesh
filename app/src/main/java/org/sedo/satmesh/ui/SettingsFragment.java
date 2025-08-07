package org.sedo.satmesh.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.ArrayRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
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

/**
 * Fragment to display and manage app settings.
 * It allows users to customize theme, font size, username, and view their node ID.
 *
 * @author hsedo777
 */
public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

	/**
	 * Default theme index, used if no theme is set.
	 */
	public static final int DEFAULT_THEME_INDEX = 2;
	/**
	 * Default font size index, used if no font size is set.
	 */
	public static final int DEFAULT_FONT_SIZE_INDEX = 1;

	// Argument key for passing the local node address name to the fragment.
	private static final String ARG_HOST_ADDRESS_NAME = "host_node_address_name";

	// Executor service for background tasks.
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();
	// Repository for accessing node data.
	private NodeRepository nodeRepository;
	// The local node object.
	private Node hostNode;
	// The address name of the local node.
	private String hostNodeAddressName;
	// Listener for display name changes.
	private UserDisplayNameListener displayNameListener;

	/**
	 * Creates a new instance of SettingsFragment.
	 *
	 * @param hostNodeAddressName The address name of the local node.
	 * @return A new instance of SettingsFragment.
	 */
	public static SettingsFragment newInstance(String hostNodeAddressName) {
		SettingsFragment fragment = new SettingsFragment();
		Bundle args = new Bundle();
		args.putString(ARG_HOST_ADDRESS_NAME, hostNodeAddressName);
		fragment.setArguments(args);
		return fragment;
	}

	/**
	 * Retrieves the array of theme values.
	 *
	 * @param context The context.
	 * @return An array of theme values.
	 */
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
		outState.putString(ARG_HOST_ADDRESS_NAME, hostNodeAddressName);
	}

	@Override
	public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
		setPreferencesFromResource(R.xml.preferences, rootKey);

		if (getArguments() != null) {
			hostNodeAddressName = getArguments().getString(ARG_HOST_ADDRESS_NAME);
		} else if (savedInstanceState != null) {
			hostNodeAddressName = savedInstanceState.getString(ARG_HOST_ADDRESS_NAME);
		}

		setupThemePreference();
		setupFontSizePreference();
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
		} else if (key.equals(getString(R.string.pref_key_font_size))) {
			// Re-set the summary to reflect the change, as the system might not do it automatically
			// for list preferences if the entries and values are different.
			setupFontSizePreference();
		}
	}

	/**
	 * Sets up a regular preference with a summary provider.
	 *
	 * @param keyResId          The resource ID of the preference key.
	 * @param valuesResId       The resource ID of the preference values array.
	 * @param entriesResId      The resource ID of the preference entries array, i.e. its localized values.
	 * @param defaultValueIndex The index of the default value in the values array.
	 */
	private void setupRegularPreference(@StringRes int keyResId, @ArrayRes int valuesResId, @ArrayRes int entriesResId, int defaultValueIndex) {
		final String key = getString(keyResId);
		Preference preference = findPreference(key);
		if (preference != null) {
			SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
			String[] preferenceValues = getResources().getStringArray(valuesResId);
			String currentValue = sharedPreferences.getString(key, preferenceValues[defaultValueIndex]);
			// Convert `currentValue` to its locale sensitive value
			String[] localizedPreferenceEntries = getResources().getStringArray(entriesResId);
			int index = Arrays.asList(preferenceValues).indexOf(currentValue);
			if (index != -1) {
				preference.setSummary(localizedPreferenceEntries[index]);
			} else {
				// Fallback to default value if current value is not found
				preference.setSummary(localizedPreferenceEntries[defaultValueIndex]);
			}
		}
	}

	/**
	 * Sets up the theme preference.
	 */
	private void setupThemePreference() {
		// Set up theme preference
		setupRegularPreference(R.string.pref_key_theme, R.array.pref_theme_values, R.array.pref_theme_entries, DEFAULT_THEME_INDEX);
	}

	/**
	 * Sets up the font size preference.
	 */
	private void setupFontSizePreference() {
		// Set up font size preference
		setupRegularPreference(R.string.pref_key_font_size, R.array.pref_font_size_values, R.array.pref_font_size_entries, DEFAULT_FONT_SIZE_INDEX);
	}

	/**
	 * Sets up the username preference.
	 */
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

	/**
	 * Sets up the node ID preference.
	 */
	private void setupNodeIdPreference() {
		Preference preference = findPreference(getString(R.string.pref_key_node_id));
		if (preference != null) {
			preference.setSummary(hostNodeAddressName);
			preference.setOnPreferenceClickListener(pref -> {
				CharSequence summary = pref.getSummary();
				if (summary != null) {
					copyNodeIdToClipboard(summary.toString());
				}
				return true;
			});
		}
	}

	/**
	 * Loads the host node from the repository and refreshes the UI.
	 */
	private void loadHostNodeAndRefreshUI() {
		if (hostNodeAddressName == null) {
			return;
		}

		executorService.execute(() -> {
			hostNode = nodeRepository.findNodeSync(hostNodeAddressName);

			if (hostNode != null) {
				refreshPreferenceSummaries();
			}
			//else: Undesired behavior, handle appropriately
		});
	}

	/**
	 * Refreshes the summaries of preferences that depend on the host node data.
	 */
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
					// Fallback to localNodeUuid if hostNode or addressName is null
					nodeIdPreference.setSummary(hostNodeAddressName);
				}
			});
		}
	}

	/**
	 * Updates the username in the repository.
	 *
	 * @param newUsername The new username.
	 */
	private void updateUsernameInRepository(@Nullable String newUsername) {
		boolean empty = newUsername == null || newUsername.trim().isEmpty();
		if (!Utils.isUsernameValid(newUsername)) {
			Toast.makeText(requireContext(),
					empty ? R.string.username_cannot_be_empty : R.string.username_invalid
					, Toast.LENGTH_SHORT).show();
			EditTextPreference usernamePref = findPreference(getString(R.string.pref_key_username));
			if (usernamePref != null && hostNode != null) {
				// Revert to the old username in the preference display
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
					if (isAdded()) { // Check if fragment is still added to an activity
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
							// Also revert the preference text on the UI thread
							if (isAdded()) {
								requireActivity().runOnUiThread(() -> {
									EditTextPreference usernamePref = findPreference(getString(R.string.pref_key_username));
									if (usernamePref != null) {
										usernamePref.setText(oldUsername);
									}
									Toast.makeText(requireContext(), R.string.username_update_failed, Toast.LENGTH_SHORT).show();
								});
							}
						}
					}
				});
			} else {
				// This case should ideally not happen if localNodeUuid is always valid
				// and loadHostNodeAndRefreshUI successfully loads the node.
				requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), R.string.username_update_failed_no_node, Toast.LENGTH_SHORT).show());
			}
		});
	}

	/**
	 * Copies the node ID to the clipboard.
	 *
	 * @param nodeId The node ID to copy.
	 */
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
