package org.sedo.satmesh.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.sedo.satmesh.databinding.FragmentNearbyDiscoveryBinding;
import org.sedo.satmesh.ui.adapter.NearbyDiscoveryAdapter;
import org.sedo.satmesh.ui.adapter.NearbyDiscoveryAdapter.OnNodeClickListener;
import org.sedo.satmesh.ui.data.NodeDiscoveryItem;
import org.sedo.satmesh.ui.data.NodeState;
import org.sedo.satmesh.utils.Constants;

import java.util.List;

public class NearbyDiscoveryFragment extends Fragment {

	private static final String HOST_DEVICE_NAME = "host_name";
	private static final String TAG = Constants.TAG_DISCOVERY_FRAGMENT;

	private DiscussionListener listener;
	private NearbyDiscoveryViewModel viewModel;
	private NearbyDiscoveryAdapter adapter;
	private FragmentNearbyDiscoveryBinding binding;
	private String hostDeviceName;

	private AppHomeListener homeListener;

	public NearbyDiscoveryFragment() {
		// Required public default constructor
	}

	public static NearbyDiscoveryFragment newInstance(@NonNull String deviceLocalName) {
		NearbyDiscoveryFragment fragment = new NearbyDiscoveryFragment();
		Bundle bundle = new Bundle();
		bundle.putString(HOST_DEVICE_NAME, deviceLocalName);
		fragment.setArguments(bundle);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof DiscussionListener && context instanceof AppHomeListener) {
			listener = (DiscussionListener) context;
			homeListener = (AppHomeListener) context;
		} else {
			throw new RuntimeException("The activity must implement interface 'DiscoveryFragmentListener' and `AppHomeListener`");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		listener = null;
		homeListener = null;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (getArguments() != null) {
			// Initial instantiation
			hostDeviceName = getArguments().getString(HOST_DEVICE_NAME);
		}
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		binding = FragmentNearbyDiscoveryBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(this, ViewModelFactory.getInstance(requireActivity().getApplication())).get(NearbyDiscoveryViewModel.class);
		if (hostDeviceName == null) {
			// Fragment recreated
			hostDeviceName = viewModel.getHostDeviceName();
		} else {
			// First instance
			viewModel.setHostDeviceName(hostDeviceName);
		}
		viewModel.getDescriptionState().observe(getViewLifecycleOwner(), descriptionState -> {
			binding.nearbyDescription.setText(descriptionState.text);
			binding.nearbyDescription.setTextColor(ContextCompat.getColor(requireContext(), descriptionState.color));
		});

		viewModel.getRecyclerVisibility().observe(getViewLifecycleOwner(), binding.nearbyNodesRecyclerView::setVisibility);
		viewModel.getEmptyStateTextView().observe(getViewLifecycleOwner(), binding.emptyStateText::setVisibility);
		viewModel.getProgressBar().observe(getViewLifecycleOwner(), binding.progressBar::setVisibility);

		// Observe the combined list from ViewModel
		viewModel.getDisplayNodeListLiveData().observe(getViewLifecycleOwner(), this::onNodesChanged);

		adapter = new NearbyDiscoveryAdapter();
		adapter.attachOnNodeClickListener(new OnNodeClickListener() {
			@Override
			public void onClick(@NonNull NodeDiscoveryItem item) {
				if (item.state != NodeState.ON_CONNECTED) {
					String endpointId = viewModel.getNearbyManager().getLinkedEndpointId(item.getAddressName());
					if (endpointId != null) {
						Log.d(TAG, viewModel.getHostDeviceName() + " request connection to " + endpointId + "(" + item.getAddressName() + ")");
						viewModel.getNearbyManager().requestConnection(endpointId, item.getAddressName());
					} else {
						Log.w(TAG, "Cannot request connection: No endpoint ID found for " + item.getAddressName());
					}
				} else {
					// Redirect the user to chat fragment
					if (listener != null) {
						listener.discussWith(item.node, false);
					}
				}
			}

			@Override
			public void onLongClick(@NonNull NodeDiscoveryItem item) {
				if (item.state == NodeState.ON_CONNECTED) {
					String endpointId = viewModel.getNearbyManager().getLinkedEndpointId(item.getAddressName());
					if (endpointId != null) {
						Log.d(TAG, viewModel.getHostDeviceName() + " request disconnect from " + endpointId + "(" + item.getAddressName() + ")");
						viewModel.getNearbyManager().disconnectFromEndpoint(endpointId);
					}
				}
			}
		});
		binding.nearbyNodesRecyclerView.setAdapter(adapter);
		binding.nearbyTitle.setOnClickListener(v -> viewModel.reloadNodes());

		requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (requireActivity().getSupportFragmentManager().getBackStackEntryCount() > 1) { // use 1 to count the current fragment
					requireActivity().getSupportFragmentManager().popBackStack();
				} else if (homeListener != null) {
					homeListener.backToHome();
				}
			}
		});
	}

	private void onNodesChanged(@Nullable List<NodeDiscoveryItem> items) {
		Log.d(TAG, "Nodes list updated in Fragment: " + (items != null ? items.size() : "0") + " nodes.");
		adapter.submitList(items);

		viewModel.getProgressBar().postValue(View.GONE); // Always hide progress bar after results are processed
	}
}
