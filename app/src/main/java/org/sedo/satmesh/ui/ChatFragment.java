package org.sedo.satmesh.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentChatBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.adapter.ChatAdapter;
import org.sedo.satmesh.utils.Constants;

import java.util.Objects;

public class ChatFragment extends Fragment {

	private static final String ARG_HOST_PREFIX = "host_node_";
	private static final String ARG_REMOTE_PREFIX = "remote_node_";

	private ChatViewModel viewModel;
	private ChatAdapter adapter;
	private FragmentChatBinding binding;

	private Node hostNode;
	private Node remoteNode;

	public ChatFragment() {
		// Required empty public constructor
	}

	public static ChatFragment newInstance(Node hostNode, Node remoteNode) {
		ChatFragment fragment = new ChatFragment();
		Bundle args = new Bundle();
		hostNode.write(args, ARG_HOST_PREFIX);
		remoteNode.write(args, ARG_REMOTE_PREFIX);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (getArguments() != null) {
			hostNode = Node.restoreFromBundle(getArguments(), ARG_HOST_PREFIX);
			remoteNode = Node.restoreFromBundle(getArguments(), ARG_REMOTE_PREFIX);
			// Ensure nodes are restored
			if (hostNode == null || hostNode.getId() == null || remoteNode == null || remoteNode.getId() == null) {
				// Alert and close the fragment
				Log.e(Constants.TAG_CHAT_FRAGMENT, "onCreate() : failed to fetch nodes from arguments or nodes are null!");
				Toast.makeText(getContext(), R.string.internal_error, Toast.LENGTH_LONG).show();
				requireActivity().getOnBackPressedDispatcher().onBackPressed();
			}
		}
		/*
		 * Else, nodes are mapped in the view model, cause `getArguments() == null ===> the fragment is recreated by android`
		 * We retrieve them later from ViewModel.
		 */
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		binding = FragmentChatBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		// Initialize ViewModel using ViewModelFactory.
		viewModel = new ViewModelProvider(this, ViewModelFactory.getInstance(requireActivity().getApplication())).get(ChatViewModel.class);

		// If ViewModel already has nodes set (e.g., after orientation change), use them.
		// Otherwise, use the ones from arguments and tell ViewModel to set them.
		if (hostNode == null || remoteNode == null) {
			hostNode = viewModel.getHostNodeLiveData().getValue();
			remoteNode = viewModel.getRemoteNodeLiveData().getValue();
		}

		// IMPORTANT: Ensure hostNode and remoteNode are not null before proceeding
		if (hostNode == null || remoteNode == null) {
			Log.e(Constants.TAG_CHAT_FRAGMENT, "onViewCreated() : hostNode or remoteNode is null after ViewModel initialization!");
			Toast.makeText(getContext(), R.string.internal_error, Toast.LENGTH_LONG).show();
			requireActivity().getOnBackPressedDispatcher().onBackPressed();
			return; // Prevent NullPointerException
		}

		Toolbar toolbar = binding.chatToolbar;
		toolbar.setNavigationOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

		// IMPROVED: Toolbar click listener for potential manual re-initiation or info display
		toolbar.setOnClickListener(v -> {
			// If connection is not active (i.e., not secure or not connected Nearby)
			if (!Boolean.TRUE.equals(viewModel.getConnectionActive().getValue())) {
				// Attempt to re-initiate key exchange if there's an endpoint available
				String endpointId = viewModel.getNearbyManager().getLinkedEndpointId(remoteNode.getAddressName());
				if (endpointId != null) {
					Toast.makeText(getContext(), R.string.re_initiating_key_exchange, Toast.LENGTH_SHORT).show();
					viewModel.getNearbyManager().requestConnection(endpointId, remoteNode.getAddressName()); // Re-request connection if needed
					viewModel.setConversationNodes(hostNode, remoteNode); // This will trigger handleInitialKeyExchange
				} else {
					Toast.makeText(getContext(), getString(R.string.error_no_direct_connection, remoteNode.getDisplayName()), Toast.LENGTH_SHORT).show();
				}
			} else {
				// If connection is active/secure, maybe show a "secured" toast or details
				Toast.makeText(getContext(), R.string.status_secure_session_active, Toast.LENGTH_SHORT).show();
			}
		});


		adapter = new ChatAdapter(hostNode.getId());
		binding.chatRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		binding.chatRecyclerView.setAdapter(adapter);

		binding.sendButton.setOnClickListener(v -> {
			String text = Objects.requireNonNull(binding.messageEditText.getText()).toString().trim();
			if (!text.isEmpty()) {
				viewModel.sendMessage(text);
				binding.messageEditText.setText("");
			}
		});

		binding.messageEditText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				// Not needed for this logic
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				// Enable send button only if text is not empty
				binding.sendButton.setEnabled(!s.toString().trim().isEmpty());
			}

			@Override
			public void afterTextChanged(Editable s) {
				// Not needed for this logic
			}
		});

		// Initial state: disable send button if text field is empty
		binding.sendButton.setEnabled(!Objects.requireNonNullElse(binding.messageEditText.getText(), "").toString().trim().isEmpty());

		// Observers of `ViewModel`'s `LiveData`s
		viewModel.getConversation().observe(getViewLifecycleOwner(), messages -> {
			adapter.submitList(messages);
			if (!messages.isEmpty()) {
				// Scroll to bottom only if it's not already at the bottom to avoid jumpiness
				// Or always scroll if it's a new message
				binding.chatRecyclerView.scrollToPosition(messages.size() - 1);
			}
		});

		viewModel.getUiMessage().observe(getViewLifecycleOwner(), message -> {
			if (message != null && !message.isEmpty()) {
				Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
			}
		});

		// IMPROVED: Observe remoteNodeLiveData for dynamic display name updates
		viewModel.getRemoteNodeLiveData().observe(getViewLifecycleOwner(), updatedRemoteNode -> {
			if (updatedRemoteNode != null) {
				remoteNode = updatedRemoteNode; // Update local remoteNode reference
				if (updatedRemoteNode.getDisplayName() != null && !updatedRemoteNode.getDisplayName().isEmpty()) {
					toolbar.setTitle(updatedRemoteNode.getDisplayName());
				} else {
					toolbar.setTitle(getString(R.string.app_name));
				}
			}
		});

		// Current status (connected/disconnected) - determines visibility of indicator
		viewModel.getConnectionActive().observe(getViewLifecycleOwner(), isActive -> {
			Log.d(Constants.TAG_CHAT_FRAGMENT, "Connection Active: " + isActive);
			/* Show indicator only if NOT active/secure
			 * binding.connectionIndicator.setVisibility(isActive ? View.GONE : View.VISIBLE);
			 * binding.messageEditText.setEnabled(isActive); binding.sendButton.setEnabled(isActive);
			 */
			String message = viewModel.getUiMessage().getValue();
			if (Boolean.FALSE.equals(isActive) && message != null && !message.isEmpty()){
				binding.chatToolbar.setSubtitle(message);
			}
			if (Boolean.TRUE.equals(isActive)){
				binding.chatToolbar.setSubtitle(R.string.status_secure_session_active);
			}
		});

		// Detailed status (color indicator)
		viewModel.getConnectionDetailedStatus().observe(getViewLifecycleOwner(), status -> {
			if (status == null) return;
			Log.d(Constants.TAG_CHAT_FRAGMENT, "Connection Detailed Status: " + status);
			// Use ContextCompat.getColor for consistent color loading
			binding.connectionIndicator.setBackgroundColor(ContextCompat.getColor(requireContext(), status.getColorResId()));
			// To be added: Update status text next to the toolbar title if you add a TextView for it.
			// Example: binding.connectionIndicator.setTooltipText(status.getDisplayString());
		});


		// (Re)load conversation and initiate connection/key exchange process
		// This should be called AFTER all observers are set up, so they can immediately react.
		viewModel.setConversationNodes(hostNode, remoteNode);

		// Menu provider setup (no changes here, already good)
		requireActivity().addMenuProvider(new MenuProvider() {
			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
				menuInflater.inflate(R.menu.chat_menu, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
				if (menuItem.getItemId() == R.id.action_clear_chat) {
					// TODO: Implement actual chat clearing logic in ViewModel and Repository
					Toast.makeText(getContext(), R.string.clear_chat, Toast.LENGTH_SHORT).show();
					return true;
				}
				return false;
			}
		}, getViewLifecycleOwner());
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		binding = null; // Clear binding
	}
}