package org.sedo.satmesh.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.sedo.satmesh.databinding.FragmentLoadingBinding;
import org.sedo.satmesh.service.SATMeshServiceStatus;

/**
 * Loading fragment
 */
public class LoadingFragment extends Fragment {

	public static final String TAG = "LoadingFragment";
	private ServiceLoadingListener loadingListener;

	public LoadingFragment() {
		// Required empty public constructor
	}

	public static LoadingFragment newInstance() {
		return new LoadingFragment();
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof ServiceLoadingListener) {
			loadingListener = (ServiceLoadingListener) context;
		} else {
			throw new RuntimeException("The parent must implement 'ServiceLoadingListener'");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		loadingListener = null; // Free memory
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
	                         Bundle savedInstanceState) {
		// Inflate the layout for this fragment
		FragmentLoadingBinding binding = FragmentLoadingBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		SATMeshServiceStatus.getInstance().getServiceReady().observe(getViewLifecycleOwner(), aBoolean -> {
			if (Boolean.TRUE.equals(aBoolean)) {
				loadingListener.onServicesReady();
			}
		});
		requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				requireActivity().finish();
			}
		});
	}

	@Override
	public void onStop() {
		super.onStop();
		SATMeshServiceStatus.getInstance().getServiceReady().removeObservers(getViewLifecycleOwner());
	}

	public interface ServiceLoadingListener {
		void onServicesReady();
	}
}