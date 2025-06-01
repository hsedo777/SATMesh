package org.sedo.satmesh.ui;

import static org.sedo.satmesh.nearby.NearbyManager.DeviceConnectionListener;
import static org.sedo.satmesh.nearby.NearbyManager.DiscoveringListener;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import com.google.android.gms.common.api.Status;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentNearbyDiscoveryBinding;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.nearby.NearbyManager;
import org.sedo.satmesh.ui.adapter.NearbyDiscoveryAdapter;
import org.sedo.satmesh.ui.model.NodeState;
import org.sedo.satmesh.utils.Constants;

import java.util.List;

public class NearbyDiscoveryFragment extends Fragment {

	private static final int REMOVING_DElAY = 5000; // 5s

	private static final String DEVICE_LOCAL_NAME = "local_name";
	private static final String ADD_TO_BACK_STACK = "add_to_back_stack";


	private NearbyManager nearbyManager;
	/**
	 * A Handler that allows us to post back on to the UI thread.
	 */
	private final Handler uiHandler = new Handler(Looper.getMainLooper());
	private NearbyDiscoveryViewModel viewModel;
	private final DeviceConnectionListener connectionListener = new DeviceConnectionListener() {
		@Override
		public void onConnectionInitiated(String endpointId, String deviceAddressName) {
			Log.i(Constants.TAG_DISCOVERY_FRAGMENT, "onConnectionInitiated " + deviceAddressName);
			viewModel.addOrUpdateNode(viewModel.getNode(deviceAddressName), NodeState.ON_CONNECTION_INITIATED);
		}

		@Override
		public void onDeviceConnected(String endpointId, String deviceAddressName) {
			Log.i(Constants.TAG_DISCOVERY_FRAGMENT, "onDeviceConnected " + deviceAddressName);
			viewModel.addOrUpdateNode(viewModel.getNode(deviceAddressName), NodeState.ON_CONNECTED);
		}

		@Override
		public void onConnectionFailed(String deviceAddressName, Status status) {
			Log.i(Constants.TAG_DISCOVERY_FRAGMENT, "onConnectionFailed " + deviceAddressName);
			viewModel.addOrUpdateNode(viewModel.getNode(deviceAddressName), NodeState.ON_CONNECTION_FAILED);
		}

		@Override
		public void onDeviceDisconnected(String endpointId, String deviceAddressName) {
			Log.i(Constants.TAG_DISCOVERY_FRAGMENT, "onDeviceDisconnected " + deviceAddressName);
			viewModel.addOrUpdateNode(viewModel.getNode(deviceAddressName), NodeState.ON_DISCONNECTED);
			uiHandler.postDelayed(() -> viewModel.removeNode(deviceAddressName), REMOVING_DElAY);
		}
	};
	private NearbyDiscoveryAdapter adapter;
	private FragmentNearbyDiscoveryBinding binding;
	private final DiscoveringListener discoveringListener = new DiscoveringListener() {

		@Override
		public void onDiscoveringStarted() {
			// OK, may activate description view on fragment
			binding.nearbyDescription.setText(R.string.nearby_discovery_description);
			binding.nearbyDescription.setTextColor(ContextCompat.getColor(NearbyDiscoveryFragment.this.requireContext(), R.color.white));
		}

		@Override
		public void onDiscoveringFailed(Exception e) {
			binding.nearbyDescription.setText(R.string.internal_error);
			binding.nearbyDescription.setTextColor(ContextCompat.getColor(NearbyDiscoveryFragment.this.requireContext(), R.color.colorError));
			binding.nearbyNodesRecyclerView.setVisibility(View.GONE);
		}

		@Override
		public void onEndpointFound(String endpointId, String endpointName) {
			Log.i(Constants.TAG_DISCOVERY_FRAGMENT, "onEndpointFound " + endpointName);
			appendNode(endpointName, NodeState.ON_ENDPOINT_FOUND);
		}

		@Override
		public void onEndpointLost(String endpointId, String endpointName) {
			if (endpointName == null){
				return;
			}
			viewModel.addOrUpdateNode(viewModel.getNode(endpointName), NodeState.ON_ENDPOINT_LOST);
		}
	};

	public NearbyDiscoveryFragment(){}

	public static NearbyDiscoveryFragment newInstance(@NonNull String deviceLocalName, boolean addToBackStack) {
		NearbyDiscoveryFragment fragment = new NearbyDiscoveryFragment();
		Bundle bundle = new Bundle();
		bundle.putString(DEVICE_LOCAL_NAME, deviceLocalName);
		bundle.putBoolean(ADD_TO_BACK_STACK, addToBackStack);
		fragment.setArguments(bundle);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		viewModel = new ViewModelProvider(this, ViewModelFactory.getInstance()).get(NearbyDiscoveryViewModel.class);
		if (getArguments() != null){
			// Initial instantiation
			viewModel.setHostDeviceName(getArguments().getString(DEVICE_LOCAL_NAME));
			viewModel.setAddToBackStack(getArguments().getBoolean(ADD_TO_BACK_STACK));
		}
		nearbyManager = NearbyManager.getInstance(requireContext(), viewModel.getHostDeviceName());
		if (!viewModel.isAddToBackStack()) {
			requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					NearbyDiscoveryFragment.this.requireActivity().finish();
				}
			});
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

		adapter = new NearbyDiscoveryAdapter(requireContext(), viewModel::getStateForNode);
		binding.nearbyNodesRecyclerView.setAdapter(adapter);

		observeNodes();
		this.nearbyManager.addDiscoveringListener(discoveringListener);
		this.nearbyManager.addDeviceConnectionListener(connectionListener);
		for (String pending : nearbyManager.getAllPendingAddressName()){
			appendNode(pending, NodeState.ON_ENDPOINT_FOUND);
		}
		for (String incoming : nearbyManager.getAllIncomingAddressName()){
			appendNode(incoming, NodeState.ON_CONNECTION_INITIATED);
		}
		for (String connected : nearbyManager.getAllConnectedAddressName()){
			appendNode(connected, NodeState.ON_CONNECTED);
		}
	}

	private void observeNodes() {
		viewModel.observe(getViewLifecycleOwner(), this::onNodesChanged);
	}

	private void onNodesChanged(List<Node> nodeList) {
		adapter.clear();
		for (Node node : nodeList) {
			adapter.addOrUpdateNode(node);
		}

		if (nodeList.isEmpty()) {
			binding.nearbyNodesRecyclerView.setVisibility(View.GONE);
			binding.emptyStateText.setVisibility(View.VISIBLE);
		} else {
			binding.nearbyNodesRecyclerView.setVisibility(View.VISIBLE);
			binding.emptyStateText.setVisibility(View.GONE);
		}

		binding.progressBar.setVisibility(View.GONE); // stop spinner once results start coming
	}

	protected void appendNode(String endpointName, NodeState state) {
		Node node = new Node();
		node.setAddressName(endpointName);
		viewModel.addOrUpdateNode(node, state);
	}

	@Override
	public void onStart() {
		super.onStart();
		nearbyManager.startAdvertising();
		nearbyManager.startDiscovery();
	}

	@Override
	public void onStop() {
		super.onStop();
		nearbyManager.stopDiscovery();
		// Advertising may continue to in favor of routing
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		nearbyManager.removeDeviceConnectionListener(connectionListener);
		nearbyManager.removeDiscoveringListener(discoveringListener);
	}
}
