package org.sedo.satmesh.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentSearchBinding;
import org.sedo.satmesh.ui.adapter.ChatListAdapter;
import org.sedo.satmesh.ui.adapter.SearchMessageAdapter;

public class SearchFragment extends Fragment {

	private static final String TAG = "SearchFragment";
	private static final String HOST_NODE_ID_KEY = "host_node_id";

	private FragmentSearchBinding binding;
	private SearchViewModel searchViewModel;

	private ChatListAdapter discussionsAdapter;
	private SearchMessageAdapter messagesAdapter;

	public static SearchFragment newInstance(long hostNodeId) {
		SearchFragment searchFragment = new SearchFragment();
		Bundle bundle = new Bundle();
		bundle.putLong(HOST_NODE_ID_KEY, hostNodeId);
		searchFragment.setArguments(bundle);
		return searchFragment;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		// Inflate the layout using ViewBinding
		binding = FragmentSearchBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		// 1. Initialize view model
		searchViewModel = new ViewModelProvider(this, ViewModelFactory.getInstance(requireActivity().getApplication()))
				.get(SearchViewModel.class);

		// 2. Restore host node's ID
		Long hostNodeId;
		if (getArguments() != null) {// New instance
			Log.d(TAG, "Reading host node's ID from arguments bundle.");
			hostNodeId = getArguments().getLong(HOST_NODE_ID_KEY);
		} else { // Recreated fragment
			Log.d(TAG, "Restoring host node's ID from ViewModel.");
			hostNodeId = searchViewModel.getHostNodeId();
		}
		if (hostNodeId == null) {
			Log.e(TAG, "Unable to fetch the host node ID.");
			requireActivity().getOnBackPressedDispatcher().onBackPressed();
			return;
		}

		// 3. Configure RecyclerViews
		setupRecyclerViews(hostNodeId);

		// 4. Configure SearchView
		setupSearchView();

		// 5. Observe the LiveData of ViewModel
		observeViewModel();
		searchViewModel.setHostNodeIdLiveData(hostNodeId);
	}

	private void setupRecyclerViews(long hostNodeId) {
		// RecyclerView des discussions
		discussionsAdapter = new ChatListAdapter(hostNodeId);
		discussionsAdapter.setOnItemClickListener(item -> {
			// TODO
			Log.d(TAG, "Discussion clicked: " + item.remoteNode.getDisplayName());
		});
		binding.recyclerDiscussions.setLayoutManager(new LinearLayoutManager(getContext()));
		binding.recyclerDiscussions.setAdapter(discussionsAdapter);

		// RecyclerView des messages
		messagesAdapter = new SearchMessageAdapter(hostNodeId);
		messagesAdapter.setOnItemClickListener(item -> {
			// TODO
			Log.d(TAG, "Message clicked: " + item.message.getContent());
		});
		binding.recyclerMessages.setLayoutManager(new LinearLayoutManager(getContext()));
		binding.recyclerMessages.setAdapter(messagesAdapter);
	}

	private void setupSearchView() {
		customizeSearchView(binding.searchView);

		binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
			@Override
			public boolean onQueryTextSubmit(String query) {
				// Attempt to hide the key board
				InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
				if (imm != null) {
					imm.hideSoftInputFromWindow(binding.searchView.getWindowToken(), 0);
				}
				return true;
			}

			@Override
			public boolean onQueryTextChange(String newText) {
				searchViewModel.setSearchQuery(newText);
				return true;
			}
		});
	}

	private void customizeSearchView(SearchView searchView) {
		EditText searchEditText = searchView.findViewById(androidx.appcompat.R.id.search_src_text);
		@ColorInt int colorOnSurface = ContextCompat.getColor(requireContext(), R.color.colorOnSecondary);
		if (searchEditText != null) {
			searchEditText.setTextColor(colorOnSurface);
			searchEditText.setHintTextColor(colorOnSurface);
		}

		ImageView searchIcon = searchView.findViewById(androidx.appcompat.R.id.search_mag_icon);
		if (searchIcon != null) {
			searchIcon.setColorFilter(colorOnSurface);
		}

		ImageView closeButton = searchView.findViewById(androidx.appcompat.R.id.search_close_btn);
		if (closeButton != null) {
			closeButton.setColorFilter(colorOnSurface);
		}

		ImageView submitButton = searchView.findViewById(androidx.appcompat.R.id.search_go_btn);
		if (submitButton != null) {
			submitButton.setColorFilter(colorOnSurface);
		}
	}


	private void observeViewModel() {
		searchViewModel.getChatListItems().observe(getViewLifecycleOwner(), discussions -> {
			Log.d(TAG, "Discussions updated: " + (discussions != null ? discussions.size() : "null"));
			discussionsAdapter.submitList(discussions);
			updateVisibility();
		});

		searchViewModel.getSearchMessageItems().observe(getViewLifecycleOwner(), messages -> {
			Log.d(TAG, "Messages updated: " + (messages != null ? messages.size() : "null"));
			messagesAdapter.submitList(messages);
			updateVisibility();
		});
	}

	private void updateVisibility() {
		boolean hasDiscussions = discussionsAdapter.getItemCount() > 0;
		boolean hasMessages = messagesAdapter.getItemCount() > 0;

		if (hasDiscussions || hasMessages) {
			binding.textNoResults.setVisibility(View.GONE);

			binding.labelDiscussions.setVisibility(hasDiscussions ? View.VISIBLE : View.GONE);
			binding.recyclerDiscussions.setVisibility(hasDiscussions ? View.VISIBLE : View.GONE);

			binding.labelMessages.setVisibility(hasMessages ? View.VISIBLE : View.GONE);
			binding.recyclerMessages.setVisibility(hasMessages ? View.VISIBLE : View.GONE);
		} else {
			binding.textNoResults.setVisibility(View.VISIBLE);
			binding.labelDiscussions.setVisibility(View.GONE);
			binding.recyclerDiscussions.setVisibility(View.GONE);
			binding.labelMessages.setVisibility(View.GONE);
			binding.recyclerMessages.setVisibility(View.GONE);
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		binding = null;
	}
}