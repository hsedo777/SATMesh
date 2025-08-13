package org.sedo.satmesh.ui;

import static org.sedo.satmesh.utils.Constants.TAG_CHAT_FRAGMENT;

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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentChatBinding;
import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.nearby.NearbySignalMessenger;
import org.sedo.satmesh.ui.adapter.ChatAdapter;
import org.sedo.satmesh.ui.vm.ChatViewModel;
import org.sedo.satmesh.ui.vm.ViewModelFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class ChatFragment extends Fragment {

	public static final String MESSAGE_ID_TO_SCROLL_KEY = "message_id_to_scroll";
	private static final String TAG = TAG_CHAT_FRAGMENT;
	private static final String ARG_HOST_PREFIX = "host_node_";
	private static final String ARG_REMOTE_PREFIX = "remote_node_";

	private AppHomeListener homeListener;
	private ChatViewModel viewModel;
	private ChatAdapter adapter;
	private Long messageIdToScrollTo = null;

	private ActionMode currentActionMode;
	private FragmentChatBinding binding;
	private Node hostNode;
	// Implementation of ActionMode.Callback
	private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.getMenuInflater().inflate(R.menu.menu_chat_contextual_action_mode, menu);
			// Access the ActionMode's view and set its background color
			View decorView = requireActivity().getWindow().getDecorView();
			View actionModeView = decorView.findViewById(androidx.appcompat.R.id.action_mode_bar);
			if (actionModeView != null) {
				actionModeView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.toolbar_background));
			}
			return true;
		}

		private boolean canClaimReadAckOn(Message message) {
			return message != null && !message.isRead() && message.isDelivered()
					&& Objects.equals(ChatFragment.this.hostNode.getId(), message.getSenderNodeId());
		}

		private boolean canBeResend(Message message) {
			Node recipient = viewModel.getRemoteNodeLiveData().getValue();
			return message != null && message.isSentTo(recipient) && !message.hadReceivedAck()
					&& !message.isOnTransmissionQueue();
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
			MenuItem claimItem = menu.findItem(R.id.action_claim_ack);
			MenuItem resendItem = menu.findItem(R.id.action_resend);
			Message message = adapter.getIfSingleSelected();
			if (claimItem != null) {
				claimItem.setVisible(canClaimReadAckOn(message));
			}
			if (resendItem != null) {
				resendItem.setVisible(canBeResend(message));
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
				Log.d(TAG, "Attempting to delete " + selectedMessageIds.size() + " message(s)");
				new AlertDialog.Builder(requireContext())
						.setTitle(R.string.delete_messages_dialog_title)
						.setMessage(R.string.delete_messages_dialog_message)
						.setPositiveButton(R.string.delete_button_text, (dialog, which) -> {
							Log.d(TAG, "Confirmation received. Deleting " + selectedMessageIds.size() + " message(s)");
							viewModel.deleteMessagesById(new ArrayList<>(selectedMessageIds));
							mode.finish();
						})
						.setNegativeButton(R.string.cancel, (dialog, which) -> {
							Log.d(TAG, "Deletion cancelled by user.");
							dialog.dismiss();
							mode.finish();
						})
						.setIcon(android.R.drawable.ic_dialog_alert)
						.show();

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
					if (UiUtils.copyToClipboard(requireContext(), copiedText.toString(), "SatMesh Message")) {
						Snackbar.make(binding.getRoot(), R.string.message_copied, Snackbar.LENGTH_SHORT).show();
					}
				}
				mode.finish();
				return true;
			} else if (id == R.id.action_claim_ack) {
				if (selectedMessageIds.size() == 1) {
					Message message = adapter.getIfSingleSelected();
					if (canClaimReadAckOn(message)) {
						viewModel.claimMessageReadAck(message);
						Toast.makeText(requireContext(), R.string.action_claim_ack_ongoing, Toast.LENGTH_SHORT).show();
					}
				}
				return true;
			} else if (id == R.id.action_resend) {
				Message message = adapter.getIfSingleSelected();
				if (canBeResend(message)) {
					viewModel.requestManualResend(Objects.requireNonNull(message), isAborted -> {
						if (isAborted) {
							requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), R.string.message_resend_failed, Toast.LENGTH_SHORT).show());
						}
					});
				}
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
	private Node remoteNode;

	public ChatFragment() {
		// Required empty public constructor
	}

	/**
	 * Creates a new instance of {@link ChatFragment} to display a conversation
	 * between two specific nodes, with optional additional arguments.
	 * This method is the recommended way to instantiate the fragment with the necessary data.
	 *
	 * @param hostNode   The local (host) node of the conversation. This node typically represents the device running the application.
	 * @param remoteNode The remote node with which the conversation is established.
	 * @param extra      An optional {@link Bundle} containing additional arguments for the fragment.
	 *                   Currently, only the key {@link #MESSAGE_ID_TO_SCROLL_KEY} is supported for scrolling
	 *                   to a specific message after the fragment loads. If this ID is provided,
	 *                   the chat RecyclerView will attempt to scroll to that message. Can be {@code null}.
	 * @return A new instance of {@link ChatFragment} configured with the nodes and additional arguments.
	 */
	public static ChatFragment newInstance(Node hostNode, Node remoteNode, @Nullable Bundle extra) {
		ChatFragment fragment = new ChatFragment();
		Bundle args = new Bundle();
		hostNode.write(args, ARG_HOST_PREFIX);
		remoteNode.write(args, ARG_REMOTE_PREFIX);
		if (extra != null) {
			args.putAll(extra);
		}
		fragment.setArguments(args);
		return fragment;
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
		if (!retrieveInitialData()) {
			return; // Exit if nodes are not valid
		}

		// Setup Toolbar
		setupToolbar();

		// Setup RecyclerView and Adapter
		setupChatRecyclerView();

		// Setup Message Input (EditText and Send Button)
		setupMessageInput();

		// Observe ViewModel LiveData
		observeViewModel();

		// (Re)load conversation and initiate connection/key exchange process
		// This should be called AFTER all observers are set up, so they can immediately react.
		viewModel.setConversationNodes(hostNode, remoteNode);

		// Setup Back Press handling
		setupOnBackPressed();
	}

	/**
	 * Retrieve and Validate Nodes
	 */
	private boolean retrieveInitialData() {
		if (getArguments() != null) {
			hostNode = Node.restoreFromBundle(getArguments(), ARG_HOST_PREFIX);
			remoteNode = Node.restoreFromBundle(getArguments(), ARG_REMOTE_PREFIX);
			// Ensure nodes are restored
			if (hostNode == null || hostNode.getId() == null || remoteNode == null || remoteNode.getId() == null) {
				// Alert and close the fragment
				Log.e(TAG, "onCreate() : failed to fetch nodes from arguments or nodes are null!");
				Toast.makeText(getContext(), R.string.internal_error, Toast.LENGTH_LONG).show();
				onBackPressed();
				return false;
			}
			if (getArguments().containsKey(MESSAGE_ID_TO_SCROLL_KEY)) {
				messageIdToScrollTo = getArguments().getLong(MESSAGE_ID_TO_SCROLL_KEY);
				Log.d(TAG, "Message ID to scroll to: " + messageIdToScrollTo);
				// Ensure the value is used only one time
				getArguments().remove(MESSAGE_ID_TO_SCROLL_KEY);
			}
		}
		/*
		 * Else, nodes are mapped in the view model, cause `getArguments() == null ===> the fragment is recreated by android`
		 * We retrieve them later from ViewModel.
		 */
		if (hostNode == null || remoteNode == null) {
			hostNode = viewModel.getHostNodeLiveData().getValue();
			remoteNode = viewModel.getRemoteNodeLiveData().getValue();
		}

		// IMPORTANT: Ensure hostNode and remoteNode are not null before proceeding
		if (hostNode == null || remoteNode == null) {
			Log.e(TAG, "retrieveAndValidateNodes() : hostNode or remoteNode is null! Cannot proceed.");
			Toast.makeText(getContext(), R.string.internal_error, Toast.LENGTH_LONG).show();
			requireActivity().getOnBackPressedDispatcher().onBackPressed();
			return false; // Indicate failure
		}
		NearbySignalMessenger.getInstance().setCurrentRemote(remoteNode);
		return true; // Indicate success
	}

	private void setupToolbar() {
		Toolbar toolbar = binding.chatToolbar;
		toolbar.setNavigationOnClickListener(v -> onBackPressed());

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
			toolbar.getOverflowIcon().setTint(ContextCompat.getColor(requireContext(), R.color.toolbar_text));
		}

		toolbar.setOnMenuItemClickListener(menuItem -> {
			int itemId = menuItem.getItemId();
			if (itemId == R.id.action_clear_chat) {
				new AlertDialog.Builder(requireContext())
						.setTitle(R.string.clear_chat_dialog_title)
						.setMessage(R.string.clear_chat_dialog_message)
						.setPositiveButton(R.string.delete_button_text, (dialog, which) -> {
							Node remote = viewModel.getRemoteNodeLiveData().getValue();
							String with = remote == null ? "unknown" : remote.getAddressName();
							Log.d(TAG, "Confirmation received. Clearing chat with " + with);
							viewModel.clearChat();
						})
						.setNegativeButton(R.string.cancel, (dialog, which) -> {
							Log.d(TAG, "Chat clearing cancelled by user.");
							dialog.dismiss();
						})
						.setIcon(android.R.drawable.ic_dialog_alert)
						.show();
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
	}

	private void setupChatRecyclerView() {
		adapter = new ChatAdapter(hostNode.getId());
		LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
		layoutManager.setStackFromEnd(true);
		binding.chatRecyclerView.setLayoutManager(layoutManager);
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

		adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
			@Override
			public void onItemRangeInserted(int positionStart, int itemCount) { // Catch new insertion
				super.onItemRangeInserted(positionStart, itemCount);

				if (messageIdToScrollTo != null) {
					scrollToSpecificMessage(messageIdToScrollTo);
					messageIdToScrollTo = null;// Scroll once
				}
			}

			@Override
			public void onChanged() { // Handle submitting new list
				super.onChanged();

				if (messageIdToScrollTo != null) {
					scrollToSpecificMessage(messageIdToScrollTo);
					messageIdToScrollTo = null;// Scroll once
				}
			}
		});
	}

	private void setupMessageInput() {
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
	}

	private void observeViewModel() {
		// Observers of `ViewModel`'s `LiveData`s
		viewModel.getConversation().observe(getViewLifecycleOwner(), messages -> {
			adapter.submitList(messages);
			if (messages != null && !messages.isEmpty()) {
				// Scroll to bottom only if it's not already at the bottom to avoid jumpiness
				// Or always scroll if it's a new message
				binding.chatRecyclerView.scrollToPosition(messages.size() - 1);
			}
		});

		viewModel.getUiMessage().observe(getViewLifecycleOwner(), message -> {
			if (message != null && !message.isEmpty()) {
				Log.d(TAG, message);
				Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
			}
		});

		// Observe remoteNodeLiveData for dynamic display name updates
		viewModel.getRemoteNodeLiveData().observe(getViewLifecycleOwner(), updatedRemoteNode -> {
			if (updatedRemoteNode != null) {
				remoteNode = updatedRemoteNode; // Update local remoteNode reference
				if (updatedRemoteNode.getDisplayName() != null && !updatedRemoteNode.getDisplayName().isEmpty()) {
					binding.chatToolbar.setTitle(updatedRemoteNode.getDisplayName());
				} else {
					binding.chatToolbar.setTitle(getString(R.string.app_name));
				}
			}
		});

		// Current status (connected/disconnected) - determines visibility of indicator
		viewModel.getConnectionActive().observe(getViewLifecycleOwner(), isActive -> {
			Log.d(TAG, "Connection Active: " + isActive);
			String message = viewModel.getUiMessage().getValue();
			if (Boolean.FALSE.equals(isActive) && message != null && !message.isEmpty()) {
				binding.chatToolbar.setSubtitle(message);
				binding.chatToolbar.setSubtitleTextColor(ContextCompat.getColor(requireContext(), R.color.error));
			}
			if (Boolean.TRUE.equals(isActive)) {
				binding.chatToolbar.setSubtitle(R.string.status_secure_session_active);
				binding.chatToolbar.setSubtitleTextColor(ContextCompat.getColor(requireContext(), R.color.toolbar_text));
			}
		});

		// Detailed status (color indicator)
		viewModel.getConnectionDetailedStatus().observe(getViewLifecycleOwner(), status -> {
			if (status == null) {
				binding.connectionIndicator.setVisibility(View.GONE);
				return;
			}
			Log.d(TAG, "Connection Detailed Status: " + status);
			// Use ContextCompat.getColor for consistent color loading
			binding.connectionIndicator.getBackground().setTint(ContextCompat.getColor(requireContext(), status.getColorResId()));
			binding.connectionIndicator.setVisibility(View.VISIBLE);
			// To be added: Update status text next to the toolbar title if you add a TextView for it.
			// Example: binding.connectionIndicator.setTooltipText(status.getDisplayString());
		});
	}

	private void setupOnBackPressed() {
		requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				onBackPressed();
			}
		});
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof AppHomeListener) {
			homeListener = (AppHomeListener) context;
		} else {
			throw new RuntimeException("User of fragment ChatFragment must implement interface `ChatListAccessor`");
		}
	}

	private void onBackPressed() {
		if (currentActionMode != null)
			currentActionMode.finish();
		if (homeListener != null) {
			homeListener.backToHome();
		}
	}

	/**
	 * Scrolls the RecyclerView to the message with the given ID.
	 *
	 * @param messageId The ID of the message to scroll to.
	 */
	private void scrollToSpecificMessage(long messageId) {
		List<Message> currentMessages = adapter.getCurrentList();
		Message message = currentMessages.stream().filter(m -> Long.valueOf(messageId).equals(m.getId())).findFirst().orElse(null);
		int position = message != null ? currentMessages.indexOf(message) : -1;

		if (position != -1) {
			// Use post to ensure the recycler is rendered before scroll
			binding.chatRecyclerView.post(() -> {
				LinearLayoutManager layoutManager = (LinearLayoutManager) binding.chatRecyclerView.getLayoutManager();
				if (layoutManager != null) {
					int offset = binding.chatRecyclerView.getHeight() / 2;
					View view = layoutManager.findViewByPosition(position);
					if (view != null) {
						offset -= view.getHeight() / 2;
					}
					layoutManager.scrollToPositionWithOffset(position, offset);

					// Delay applying the animation slightly to ensure the view is fully rendered after scroll
					binding.chatRecyclerView.postDelayed(() -> {
						// Find the ViewHolder for the scrolled-to item
						RecyclerView.ViewHolder viewHolder = binding.chatRecyclerView.findViewHolderForAdapterPosition(position);
						if (viewHolder != null) {
							View itemView = viewHolder.itemView; // Get the root view of the message item

							// Load the animation from XML
							android.view.animation.Animation blinkAnimation =
									android.view.animation.AnimationUtils.loadAnimation(getContext(), R.anim.blink_animation);

							// Start the animation on the item view
							itemView.startAnimation(blinkAnimation);
							Log.d(TAG, "Applied blink animation to message at position: " + position);
						} else {
							Log.w(TAG, "ViewHolder not found for position " + position + " after scroll.");
						}
					}, 100); // Small delay (e.g., 100ms) after scroll
				} else {
					binding.chatRecyclerView.scrollToPosition(position);
				}
				Log.d(TAG, "Scrolled to message at position: " + position + " (ID: " + messageId + ")");
			});
		} else {
			Log.w(TAG, "Message with ID " + messageId + " not found in the list.");
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		binding = null; // Clear binding
		NearbySignalMessenger.getInstance().setCurrentRemote(null);
	}

	// Implementation of OnMessageLongClickListener
	public void onMessageLongClick(@NonNull Message message, @NonNull ChatAdapter.MessageViewHolder ignoredHolder) {
		if (currentActionMode == null) {
			currentActionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(actionModeCallback);
		}
		toggleMessageSelection(message.getId());
		if (currentActionMode != null) {
			currentActionMode.setTitle(getString(R.string.selected_count, adapter.getSelectedCount()));
			currentActionMode.invalidate();
		}
	}

	// Implementation of OnMessageClickListener
	public void onMessageClick(@NonNull Message message, @NonNull ChatAdapter.MessageViewHolder holder) {
		if (currentActionMode != null) {
			toggleMessageSelection(message.getId());
			if (currentActionMode != null) {
				currentActionMode.setTitle(getString(R.string.selected_count, adapter.getSelectedCount()));
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