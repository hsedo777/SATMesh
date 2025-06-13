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
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.ui.adapter.NearbyDiscoveryAdapter;
import org.sedo.satmesh.ui.adapter.NearbyDiscoveryAdapter.OnNodeClickListener;
import org.sedo.satmesh.ui.data.NodeState;
import org.sedo.satmesh.utils.Constants;

import java.util.List;

public class NearbyDiscoveryFragment extends Fragment {

	private static final String HOST_DEVICE_NAME = "host_name";
	private static final String ADD_TO_BACK_STACK = "add_to_back_stack";
	private static final String TAG = Constants.TAG_DISCOVERY_FRAGMENT;

	private DiscussionListener listener;
	private NearbyDiscoveryViewModel viewModel;
	private NearbyDiscoveryAdapter adapter;
	private FragmentNearbyDiscoveryBinding binding;
	private String hostDeviceName;
	private Boolean addToBackStack;

	public NearbyDiscoveryFragment() {
		// Required public default constructor
	}

	public static NearbyDiscoveryFragment newInstance(@NonNull String deviceLocalName, boolean addToBackStack) {
		NearbyDiscoveryFragment fragment = new NearbyDiscoveryFragment();
		Bundle bundle = new Bundle();
		bundle.putString(HOST_DEVICE_NAME, deviceLocalName);
		bundle.putBoolean(ADD_TO_BACK_STACK, addToBackStack);
		fragment.setArguments(bundle);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof DiscussionListener) {
			listener = (DiscussionListener) context;
		} else {
			throw new RuntimeException("The activity must implement interface 'DiscoveryFragmentListener'");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		listener = null;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (getArguments() != null) {
			// Initial instantiation
			hostDeviceName = getArguments().getString(HOST_DEVICE_NAME);
			addToBackStack = getArguments().getBoolean(ADD_TO_BACK_STACK);
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
		if (hostDeviceName == null || addToBackStack == null) {
			// Fragment recreated
			hostDeviceName = viewModel.getHostDeviceName();
			addToBackStack = viewModel.isAddToBackStack();
		} else {
			// First instance
			viewModel.setHostDeviceName(hostDeviceName);
			viewModel.setAddToBackStack(addToBackStack);
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

		if (!viewModel.isAddToBackStack()) {
			requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					NearbyDiscoveryFragment.this.requireActivity().finish();
				}
			});
		}
		adapter = new NearbyDiscoveryAdapter(requireContext(), viewModel::getStateForNode);
		adapter.attachOnNodeClickListener(new OnNodeClickListener() {
			@Override
			public void onClick(Node node, NodeState state) {
				if (state == null)
					return;
				if (state != NodeState.ON_CONNECTED) {
					String endpointId = viewModel.getNearbyManager().getLinkedEndpointId(node.getAddressName());
					if (endpointId != null) {
						Log.d(TAG, viewModel.getHostDeviceName() + " request connection to " + endpointId + "(" + node.getAddressName() + ")");
						viewModel.getNearbyManager().requestConnection(endpointId, node.getAddressName());
					} else {
						Log.w(TAG, "Cannot request connection: No endpoint ID found for " + node.getAddressName());
					}
				} else {
					// Redirect the user to chat fragment
					if (listener != null) {
						listener.discussWith(node);
					}
				}
			}

			@Override
			public void onLongClick(Node node, NodeState state) {
				if (state == NodeState.ON_CONNECTED && node != null) {
					String endpointId = viewModel.getNearbyManager().getLinkedEndpointId(node.getAddressName());
					if (endpointId != null) {
						Log.d(TAG, viewModel.getHostDeviceName() + " request disconnect from " + endpointId + "(" + node.getAddressName() + ")");
						viewModel.getNearbyManager().disconnectFromEndpoint(endpointId);
					}
				}
			}
		});
		binding.nearbyNodesRecyclerView.setAdapter(adapter);
		binding.nearbyTitle.setOnClickListener(v -> reload());
	}

	private void reload() {
		Log.d(TAG, "Reloading nodes list.");
		adapter.clear();
		viewModel.load();
	}

	private void onNodesChanged(List<Node> nodeList) {
		Log.d(TAG, "Nodes list updated in Fragment: " + nodeList.size() + " nodes.");
		adapter.clear();
		for (Node node : nodeList) {
			adapter.addOrUpdateNode(node);
		}

		viewModel.getProgressBar().postValue(View.GONE); // Always hide progress bar after results are processed
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.load(); // Initial load when fragment starts
	}
}
