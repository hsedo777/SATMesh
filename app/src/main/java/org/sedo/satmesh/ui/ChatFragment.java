package org.sedo.satmesh.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentChatBinding;
import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.adapter.ChatAdapter;
import org.sedo.satmesh.utils.Constants;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

public class ChatFragment extends Fragment {

	private static final String ARG_HOST_PREFIX = "host_node_";
	private static final String ARG_REMOTE_PREFIX = "remote_node_";

	private ChatListAccessor chatListAccessor;

	private ChatViewModel viewModel;
	private ChatAdapter adapter;
	private ActionMode currentActionMode;
	// Implémentation de ActionMode.Callback
	private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.getMenuInflater().inflate(R.menu.menu_chat_contextual_action_mode, menu);
			// Access the ActionMode's view and set its background color
			View decorView = requireActivity().getWindow().getDecorView();
			View actionModeView = decorView.findViewById(androidx.appcompat.R.id.action_mode_bar);
			if (actionModeView != null) {
				actionModeView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.secondaryColor));

			}
			return true;
		}

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			// Called after onCreateActionMode and whenever the ActionMode is invalidated.
			// Update menu items based on selection count.
			MenuItem copyItem = menu.findItem(R.id.action_copy);
			if (copyItem != null) {
				// Disable the copy button if more than one message is selected
				copyItem.setVisible(adapter.getSelectedCount() == 1);
			}
			return false;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			Set<Long> selectedMessageIds = adapter.getSelectedMessageIds();
			if (selectedMessageIds.isEmpty()) {
				mode.finish();
				return true;
			}

			int id = item.getItemId();
			if (id == R.id.action_delete) {
				Log.d(Constants.TAG_CHAT_FRAGMENT, "Deleting " + selectedMessageIds.size() + " message(s)");
				viewModel.deleteMessagesById(new ArrayList<>(selectedMessageIds));
				mode.finish();
				return true;
			} else if (id == R.id.action_copy) {
				StringBuilder copiedText = new StringBuilder();
				// Copy only if one message is selected
				if (selectedMessageIds.size() == 1) {
					// Since only one message is selected, we can directly get its content
					// from the first (and only) ID in the set.
					Long messageIdToCopy = selectedMessageIds.iterator().next();
					adapter.getCurrentList().stream()
							.filter(m -> m.getId().equals(messageIdToCopy))
							.findFirst()
							.ifPresent(messageToCopy -> copiedText.append(messageToCopy.getContent()));

				}

				if (copiedText.length() > 0) {
					ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
					ClipData clip = ClipData.newPlainText("SatMesh Message", copiedText.toString());
					clipboard.setPrimaryClip(clip);
					Toast.makeText(getContext(), R.string.message_copied, Toast.LENGTH_SHORT).show();
				}
				mode.finish();
				return true;
			}
			return false;
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			adapter.clearSelection();
			currentActionMode = null;
		}
	};
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
		toolbar.setNavigationOnClickListener(v -> backToChatList());

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
				Toast.makeText(getContext(), R.string.status_secure_session_active, Toast.LENGTH_LONG).show();
			}
		});
		if (toolbar.getOverflowIcon() != null) {
			toolbar.getOverflowIcon().setTint(ContextCompat.getColor(requireContext(), R.color.colorOnSecondary));
		}

		toolbar.setOnMenuItemClickListener(menuItem -> {
			int itemId = menuItem.getItemId();
			if (itemId == R.id.action_clear_chat) {
				viewModel.clearChat();
				return true;
			} else if (itemId == R.id.action_export_chat) {
				String result = viewModel.exportChatMessages();
				if (result != null) {
					Toast.makeText(getContext(), result, Toast.LENGTH_LONG).show();
				}
				return true;
			}
			return false;
		});

		adapter = new ChatAdapter(hostNode.getId());
		binding.chatRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		adapter.setOnMessageClickListener(this::onMessageClick);
		adapter.setOnMessageLongClickListener(this::onMessageLongClick);
		binding.chatRecyclerView.setAdapter(adapter);

		binding.chatRecyclerView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
			@Override
			public void onChildViewAttachedToWindow(@NonNull View view) {
				int position = binding.chatRecyclerView.getChildAdapterPosition(view);
				viewModel.markAsRead(adapter.getItem(position));
			}

			@Override
			public void onChildViewDetachedFromWindow(@NonNull View view) {
				// pass
			}
		});

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
			if (messages != null && !messages.isEmpty()) {
				// Scroll to bottom only if it's not already at the bottom to avoid jumpiness
				// Or always scroll if it's a new message
				binding.chatRecyclerView.scrollToPosition(messages.size() - 1);
			} else if (messages != null) {
				// Empty list of messages
				backToChatList();
			}
		});

		viewModel.getUiMessage().observe(getViewLifecycleOwner(), message -> {
			if (message != null && !message.isEmpty()) {
				Log.d(Constants.TAG_CHAT_FRAGMENT, message);
				Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
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
			String message = viewModel.getUiMessage().getValue();
			if (Boolean.FALSE.equals(isActive) && message != null && !message.isEmpty()) {
				binding.chatToolbar.setSubtitle(message);
				binding.chatToolbar.setSubtitleTextColor(ContextCompat.getColor(requireContext(), R.color.colorError));
			}
			if (Boolean.TRUE.equals(isActive)) {
				binding.chatToolbar.setSubtitle(R.string.status_secure_session_active);
				binding.chatToolbar.setSubtitleTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnSecondary));
			}
		});

		// Detailed status (color indicator)
		viewModel.getConnectionDetailedStatus().observe(getViewLifecycleOwner(), status -> {
			if (status == null) {
				binding.connectionIndicator.setVisibility(View.GONE);
				return;
			}
			Log.d(Constants.TAG_CHAT_FRAGMENT, "Connection Detailed Status: " + status);
			// Use ContextCompat.getColor for consistent color loading
			binding.connectionIndicator.getBackground().setTint(ContextCompat.getColor(requireContext(), status.getColorResId()));
			binding.connectionIndicator.setVisibility(View.VISIBLE);
			// To be added: Update status text next to the toolbar title if you add a TextView for it.
			// Example: binding.connectionIndicator.setTooltipText(status.getDisplayString());
		});


		// (Re)load conversation and initiate connection/key exchange process
		// This should be called AFTER all observers are set up, so they can immediately react.
		viewModel.setConversationNodes(hostNode, remoteNode);

		requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				backToChatList();
			}
		});
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof ChatListAccessor) {
			chatListAccessor = (ChatListAccessor) context;
		} else {
			throw new RuntimeException("User of fragment ChatFragment must implement interface `ChatListAccessor`");
		}
	}

	private void backToChatList() {
		if (currentActionMode != null)
			currentActionMode.finish();
		if (chatListAccessor != null) {
			chatListAccessor.moveToChatList(true, true);
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		binding = null; // Clear binding
	}

	// Implémentation of OnMessageLongClickListener
	public void onMessageLongClick(@NonNull Message message, @NonNull ChatAdapter.MessageViewHolder holder) {
		if (currentActionMode == null) {
			currentActionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(actionModeCallback);
		}
		toggleMessageSelection(message.getId());
		if (currentActionMode != null) {
			currentActionMode.setTitle(getString(R.string.selected_messages_count, adapter.getSelectedCount()));
			currentActionMode.invalidate();
		}
	}

	// Implémentation of OnMessageClickListener
	public void onMessageClick(@NonNull Message message, @NonNull ChatAdapter.MessageViewHolder holder) {
		if (currentActionMode != null) {
			toggleMessageSelection(message.getId());
			if (currentActionMode != null) {
				currentActionMode.setTitle(getString(R.string.selected_messages_count, adapter.getSelectedCount()));
				currentActionMode.invalidate();
			}
		}
	}

	// Helper method to centralize selection logic
	private void toggleMessageSelection(@NonNull Long messageId) {
		adapter.toggleSelection(messageId);
		if (!adapter.hasSelection() && currentActionMode != null) {
			currentActionMode.finish();
		}
	}
}