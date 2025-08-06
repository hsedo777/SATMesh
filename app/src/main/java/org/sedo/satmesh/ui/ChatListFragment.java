package org.sedo.satmesh.ui;

import static org.sedo.satmesh.utils.Constants.TAG_CHAT_LIST_FRAGMENT;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.sedo.satmesh.R;
import org.sedo.satmesh.SettingsActivity;
import org.sedo.satmesh.databinding.FragmentChatListBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.adapter.ChatListAdapter;
import org.sedo.satmesh.ui.data.ChatListItem;
import org.sedo.satmesh.ui.data.NodeRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatListFragment extends Fragment implements ChatListAdapter.OnItemClickListener {

	private static final String TAG = TAG_CHAT_LIST_FRAGMENT;
	private static final String ARG_HOST_NODE_ID = "host_node_id";
	private final ExecutorService executor;

	private FragmentChatListBinding binding;
	private ChatListAdapter chatListAdapter;
	private ChatListViewModel viewModel;
	private DiscussionListener discussionListener;
	private DiscussionMenuListener discussionMenuListener;
	private NearbyDiscoveryListener nearbyDiscoveryListener;
	private QuitAppListener quitAppListener;

	/**
	 * Default constructor
	 */
	public ChatListFragment() {
		executor = Executors.newSingleThreadExecutor();
	}

	/**
	 * Creates a new instance of ChatListFragment with the specified host node ID.
	 *
	 * @param hostNodeId The ID of the local (host) node for which to display chat list.
	 * @return A new instance of ChatListFragment.
	 */
	public static ChatListFragment newInstance(Long hostNodeId) {
		ChatListFragment fragment = new ChatListFragment();
		Bundle args = new Bundle();
		args.putLong(ARG_HOST_NODE_ID, hostNodeId);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		binding = FragmentChatListBinding.inflate(inflater, container, false);
		requireActivity().getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				requireActivity().finish();
			}
		});
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(
				this, ViewModelFactory.getInstance(requireActivity().getApplication())
		).get(ChatListViewModel.class);

		// Get hostNodeId again (safe to do here as it's already checked in onCreateView)
		Long hostNodeId = null;
		if (getArguments() != null && getArguments().containsKey(ARG_HOST_NODE_ID)) {
			hostNodeId = getArguments().getLong(ARG_HOST_NODE_ID);
		}

		if (hostNodeId != null && hostNodeId > 0L) {
			/*
			 * Initialize ViewModel with host ID, only for first instantiation i.e.
			 * when `hostNodeId` is fetched from `getArguments()`
			 */
			viewModel.setHostNodeIdLiveData(hostNodeId);
		} else {
			// Restore from ViewModel
			hostNodeId = viewModel.getHostNodeId();
		}

		binding.conversationsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

		// Initialize the adapter (requires hostNodeId for sender display logic)
		// Ensure hostNodeId is not null before passing it to the adapter constructor
		if (hostNodeId != null) {
			chatListAdapter = new ChatListAdapter(hostNodeId);
			chatListAdapter.setOnItemClickListener(this); // Set the item click listener
			binding.conversationsRecyclerView.setAdapter(chatListAdapter);
		} else {
			// Handle case where hostNodeId is null, stop the fragment
			Log.e(TAG, "Unable to get ID of the host node!");
			requireActivity().getOnBackPressedDispatcher().onBackPressed();
			return;
		}


		viewModel.getChatListItems().observe(getViewLifecycleOwner(), chatListItems -> {
			// Update the RecyclerView whenever the LiveData changes
			chatListAdapter.submitList(chatListItems);
			if (chatListItems != null) {
				Log.d(TAG, "Chat list items updated. Count: " + chatListItems.size());
			} else {
				// Clear the list if data becomes null
				Log.d(TAG, "Chat list items became null, clearing list.");
			}
		});
		binding.fabNodeDiscovery.setOnClickListener(view1 -> {
			if (nearbyDiscoveryListener != null) {
				nearbyDiscoveryListener.moveToDiscoveryView(false);
			}
		});

		binding.chatListAppBar.setOnMenuItemClickListener(item -> {
			int id = item.getItemId();
			if (id == R.id.action_search) {
				if (discussionMenuListener != null) {
					discussionMenuListener.moveToSearchFragment(viewModel.getHostNodeId());
				}
				return true;
			}
			if (id == R.id.menu_known_nodes) {
				if (discussionMenuListener != null) {
					discussionMenuListener.moveToKnownNodesFragment(viewModel.getHostNodeId());
				}
				return true;
			}
			if (id == R.id.menu_settings) {
				executor.execute(() -> {
					Long localNodeId = viewModel.hostNodeIdLiveData.getValue();
					if (localNodeId == null) {
						Log.e(TAG, "Failed to extract the host node ID from ViewModel");
						return;
					}
					Node localNode = new NodeRepository(requireContext()).findNodeSync(localNodeId);
					if (localNode == null) {
						Log.w(TAG, "Unable to fetch the local node from DB.");
						return;
					}
					requireActivity().runOnUiThread(() -> requireActivity().startActivity(SettingsActivity.newIntent(requireContext(), localNode.getAddressName())));
				});
				return true;
			}
			if (id == R.id.menu_renew_fingerprint) {
				new AlertDialog.Builder(requireContext())
						.setTitle(R.string.menu_renew_fingerprint)
						.setMessage(R.string.renew_fingerprint_alert_description)
						.setPositiveButton(R.string.menu_renew_fingerprint, (dialog, which) -> {
							Log.d(TAG, "Confirmation received. Reinitializing SignalManager");
							executor.execute(() -> {
								SignalManager signalManager = SignalManager.getInstance(requireContext());
								signalManager.reinitialize(new SignalManager.SignalInitializationCallback() {
									@Override
									public void onSuccess() {
										requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), R.string.renew_fingerprint_success, Toast.LENGTH_LONG).show());
									}

									@Override
									public void onError(Exception e) {
										requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), R.string.renew_fingerprint_failed, Toast.LENGTH_LONG).show());
									}
								});
							});
						})
						.setNegativeButton(R.string.negative_button_cancel, (dialog, which) -> {
							Log.d(TAG, "SignalManager reinitialization cancelled by user.");
							dialog.dismiss();
						})
						.setIcon(android.R.drawable.ic_dialog_alert)
						.show();
				return true;
			}
			if (id == R.id.chat_list_menu_quit) {
				if (quitAppListener != null) {
					quitAppListener.quitApp();
				}
				return true;
			}
			return false;
		});
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof DiscussionListener && context instanceof NearbyDiscoveryListener
				&& context instanceof DiscussionMenuListener && context instanceof QuitAppListener) {
			discussionListener = (DiscussionListener) context;
			nearbyDiscoveryListener = (NearbyDiscoveryListener) context;
			discussionMenuListener = (DiscussionMenuListener) context;
			quitAppListener = (QuitAppListener) context;
		} else {
			throw new RuntimeException("The activity must implement interfaces : NearbyDiscoveryListener" +
					", DiscussionListener, QuitAppListener and DiscussionMenuListener");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		discussionListener = null;
		nearbyDiscoveryListener = null;
		discussionMenuListener = null;
		quitAppListener = null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		executor.shutdown();
		binding = null;
	}

	// Implement OnItemClickListener
	@Override
	public void onItemClick(@NonNull ChatListItem item) {
		Log.d(TAG, "Clicked on chat item: " + item.remoteNode.getDisplayName());
		if (discussionListener != null) {
			if (item.unreadCount > 0) {
				// Try to retrieve ID of the oldest unread message
				executor.execute(() -> {
					if (isAdded()) { // Be sure the fragment still attached to its activity on the thread execution
						Long messageIdToScrollTo = viewModel.findOldestUnreadMessageId(item);
						requireActivity().runOnUiThread(() -> {
							if (discussionListener != null) { // Be sure the fragment still attached to its activity, on the ui thread execution
								discussionListener.discussWith(item.remoteNode, true, messageIdToScrollTo);
							}
						});
					}
				});
			} else {
				discussionListener.discussWith(item.remoteNode, true);
			}
		}
	}
}