package org.sedo.satmesh.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.sedo.satmesh.databinding.FragmentChatListBinding;
import org.sedo.satmesh.ui.adapter.ChatListAdapter;
import org.sedo.satmesh.ui.data.ChatListItem;

public class ChatListFragment extends Fragment implements ChatListAdapter.OnItemClickListener {

	private static final String TAG = "ChatListFragment";
	private static final String ARG_HOST_NODE_ID = "host_node_id";

	private FragmentChatListBinding binding;
	private ChatListAdapter chatListAdapter;
	private DiscussionListener discussionListener;
	private NearbyDiscoveryListener nearbyDiscoveryListener;

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
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		ChatListViewModel chatListViewModel = new ViewModelProvider(
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
			chatListViewModel.setHostNodeIdLiveData(hostNodeId);
		} else {
			// Restore from ViewModel
			hostNodeId = chatListViewModel.getHostNodeId();
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


		chatListViewModel.getChatListItems().observe(getViewLifecycleOwner(), chatListItems -> {
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
			if (nearbyDiscoveryListener != null){
				nearbyDiscoveryListener.moveToDiscoveryView(false, true);
			}
		});
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof DiscussionListener) {
			discussionListener = (DiscussionListener) context;
			if (context instanceof NearbyDiscoveryListener){
				nearbyDiscoveryListener = (NearbyDiscoveryListener) context;
			}
		} else {
			throw new RuntimeException("The activity must implement interface 'DiscoveryFragmentListener'");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		discussionListener = null;
		nearbyDiscoveryListener = null;
	}

	// Implement OnItemClickListener
	@Override
	public void onItemClick(@NonNull ChatListItem item) {
		Log.d(TAG, "Clicked on chat item: " + item.remoteNode.getDisplayName());
		if (discussionListener != null){
			discussionListener.discussWith(item.remoteNode);
		}
	}
}