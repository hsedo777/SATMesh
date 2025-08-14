package org.sedo.satmesh.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentKnownNodesBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.adapter.KnownNodesAdapter;
import org.sedo.satmesh.ui.vm.KnownNodesViewModel;
import org.sedo.satmesh.ui.vm.ViewModelFactory;

import java.util.List;

public class KnownNodesFragment extends Fragment {

	public static final String TAG = "KnownNodesFragment";
	private static final String HOST_NODE_ID_KEY = "host_node_id";

	private FragmentKnownNodesBinding binding;
	private KnownNodesViewModel knownNodesViewModel;
	private KnownNodesAdapter knownNodesAdapter;

	private ChatListAccessor chatListAccessor;
	private DiscussionListener discussionListener;

	private ActionMode actionMode; // To manage the contextual action bar
	// ActionMode Implementation
	private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			MenuInflater inflater = mode.getMenuInflater();
			inflater.inflate(R.menu.menu_known_nodes_contextual, menu);
			// Access the ActionMode's view and set its background color
			View decorView = requireActivity().getWindow().getDecorView();
			View actionModeView = decorView.findViewById(androidx.appcompat.R.id.action_mode_bar);
			if (actionModeView != null) {
				actionModeView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.secondary));
			}
			return true; // Return true for the ActionMode to be created
		}

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false; // Return false if nothing is updated
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			int id = item.getItemId();
			if (id == R.id.action_delete_nodes) {
				deleteSelectedNodes();
				mode.finish(); // Finish ActionMode after action
				return true;
			}
			return false;
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			// Called when the action mode is finished
			actionMode = null; // Clear the reference
			knownNodesAdapter.clearSelections(); // Clear all selections in the adapter
		}
	};

	/**
	 * Creates a new instance of KnownNodesFragment.
	 *
	 * @param hostNodeId The ID of the current host node, which should not be displayed.
	 * @return A new instance of KnownNodesFragment.
	 */
	public static KnownNodesFragment newInstance(long hostNodeId) {
		KnownNodesFragment fragment = new KnownNodesFragment();
		Bundle args = new Bundle();
		args.putLong(HOST_NODE_ID_KEY, hostNodeId);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof DiscussionListener && context instanceof ChatListAccessor) {
			discussionListener = (DiscussionListener) context;
			chatListAccessor = (ChatListAccessor) context;
		} else {
			throw new RuntimeException("This fragment required its parent to implement `DiscussionListener` and `ChatListAccessor`");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		discussionListener = null;
		chatListAccessor = null;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		// Inflate the layout using ViewBinding
		binding = FragmentKnownNodesBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		// 1. Initialize ViewModel
		knownNodesViewModel = new ViewModelProvider(this, ViewModelFactory.getInstance(requireActivity().getApplication()))
				.get(KnownNodesViewModel.class);

		// 2. Restore host node's ID and pass to ViewModel
		Long hostNodeId;
		if (getArguments() != null) {
			hostNodeId = getArguments().getLong(HOST_NODE_ID_KEY);
			Log.d(TAG, "Reading host node's ID from arguments bundle: " + hostNodeId);
		} else { // Handle recreation (e.g., orientation change)
			Log.e(TAG, "Host node ID restoring from ViewModel");
			hostNodeId = knownNodesViewModel.getHostNodeId();
		}

		requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
				new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (chatListAccessor != null){
					chatListAccessor.moveToChatList(false);
				}
			}
		});

		if (hostNodeId == null) {
			Log.e(TAG, "Host node ID is null. Cannot proceed.");
			requireActivity().getOnBackPressedDispatcher().onBackPressed();
			return;
		}
		knownNodesViewModel.setHostNodeId(hostNodeId);

		// 3. Setup RecyclerView
		setupRecyclerView();

		// 4. Observe ViewModel LiveData
		observeViewModel();
	}

	private void setupRecyclerView() {
		knownNodesAdapter = new KnownNodesAdapter();
		binding.recyclerKnownNodes.setLayoutManager(new LinearLayoutManager(getContext()));
		binding.recyclerKnownNodes.setAdapter(knownNodesAdapter);

		// Setup Item Click Listener (for when ActionMode is active)
		knownNodesAdapter.setOnItemClickListener(node -> {
			if (actionMode != null) { // If ActionMode is active, toggle selection
				toggleNodeSelection(node);
			} else {
				// Handle normal click behavior (e.g., open node details)
				Log.d(TAG, "Node clicked: " + node.getDisplayName());
				if (discussionListener != null){
					discussionListener.discussWith(node, true);
				}
			}
		});

		// Setup Item Long Click Listener (to start ActionMode)
		knownNodesAdapter.setOnItemLongClickListener(node -> {
			if (actionMode == null) { // Start ActionMode only if not already active
				actionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(actionModeCallback);
			}
			toggleNodeSelection(node);
		});
	}

	private void observeViewModel() {
		knownNodesViewModel.getKnownNodesExcludingHost().observe(getViewLifecycleOwner(), nodes -> {
			Log.d(TAG, "Known nodes updated: " + (nodes != null ? nodes.size() : "null"));
			knownNodesAdapter.submitList(nodes, this::updateVisibility);
		});
	}

	private void updateVisibility() {
		boolean hasNodes = knownNodesAdapter.getItemCount() > 0;
		binding.recyclerKnownNodes.setVisibility(hasNodes ? View.VISIBLE : View.GONE);
		binding.textNoKnownNodes.setVisibility(hasNodes ? View.GONE : View.VISIBLE);
	}

	/**
	 * Toggles the selection state of a given Node and updates the ActionMode title.
	 *
	 * @param node The Node object to toggle selection for.
	 */
	private void toggleNodeSelection(Node node) {
		knownNodesAdapter.toggleSelection(node.getId());
		int selectedCount = knownNodesAdapter.getSelectedItemCount();

		if (actionMode != null) {
			if (selectedCount == 0) {
				actionMode.finish(); // If no items are selected, exit ActionMode
			} else {
				actionMode.setTitle(getString(R.string.selected_count, selectedCount)); // Update title
			}
		}
	}

	/**
	 * Initiates the deletion of all currently selected nodes via the ViewModel.
	 */
	private void deleteSelectedNodes() {
		List<Node> selectedNodes = knownNodesAdapter.getSelectedNodes();
		if (!selectedNodes.isEmpty()) {
			knownNodesViewModel.deleteNodes(selectedNodes);
			Log.d(TAG, "Deleting " + selectedNodes.size() + " selected nodes.");
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		binding = null;
		if (actionMode != null) {
			actionMode.finish(); // Ensure ActionMode is dismissed when fragment view is destroyed
		}
	}
}