package org.sedo.satmesh.ui;

import android.os.Bundle;
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
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentChatBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.adapter.ChatAdapter;
import org.sedo.satmesh.ui.data.NodeState;
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
			if (hostNode.getId() == null || remoteNode.getId() == null) {
				// Alert and close the fragment
				Log.e(Constants.TAG_CHAT_FRAGMENT, "onCreate() : failed to fetch nodes from arguments!");
				Toast.makeText(getContext(), R.string.internal_error, Toast.LENGTH_LONG).show();
				requireActivity().getOnBackPressedDispatcher().onBackPressed();
			}
		}
		/*
		 * Else, nodes are mapped in the view model, cause `getArguments() == null ===> the fragment is recreated by android`
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

		viewModel = new ViewModelProvider(this, new ViewModelFactory(requireActivity().getApplication())).get(ChatViewModel.class);
		if (viewModel.areNodesSet()) {
			hostNode = viewModel.getHostNode();
			remoteNode = viewModel.getRemoteNode();
		} // else, nodes are freshly loaded from fragment' arguments

		Toolbar toolbar = binding.chatToolbar;
		toolbar.setNavigationOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
		toolbar.setOnClickListener(v -> {
			if (!NodeState.ON_CONNECTED.equals(viewModel.getConnectionDetailedStatus().getValue())
					&& viewModel.getNearbyManager().isAddressDirectlyConnected(remoteNode.getAddressName())) {
				viewModel.getConnectionActive().postValue(true);
				viewModel.getConnectionDetailedStatus().postValue(NodeState.ON_CONNECTED);
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

		// Observers of `ViewModel`'s `LiveData`s
		viewModel.getConversation().observe(getViewLifecycleOwner(), messages -> {
			adapter.submitList(messages);
			if (!messages.isEmpty())
				binding.chatRecyclerView.scrollToPosition(messages.size() - 1);
		});

		viewModel.getUiMessage().observe(getViewLifecycleOwner(), message -> {
			if (message != null && !message.isEmpty()) {
				Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
			}
		});

		viewModel.getRemoteDisplayName().observe(getViewLifecycleOwner(), displayName -> {
			if (displayName != null && !displayName.isEmpty()) {
				toolbar.setTitle(displayName);
			} else {
				toolbar.setTitle(getString(R.string.app_name)); // Fallback
			}
		});

		viewModel.getConnectionActive().observe(getViewLifecycleOwner(), isActive -> {
			Log.d("ChatFragment", "Connection Active: " + isActive);
			binding.connectionIndicator.setVisibility(isActive ? View.VISIBLE : View.GONE);
		});

		viewModel.getConnectionDetailedStatus().observe(getViewLifecycleOwner(), status -> {
			Log.d("ChatFragment", "Connection Detailed Status: " + status.name());
			binding.connectionIndicator.setBackgroundColor(getResources().getColor(status.getColorResId(), requireActivity().getTheme()));
		});

		// (Re)load conversation
		viewModel.setConversationNodes(hostNode, remoteNode);

		requireActivity().addMenuProvider(new MenuProvider() {
			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
				menuInflater.inflate(R.menu.chat_menu, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
				if (menuItem.getItemId() == R.id.action_clear_chat) {
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
		binding = null;
	}
}
