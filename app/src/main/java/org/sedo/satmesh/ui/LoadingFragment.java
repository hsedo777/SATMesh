package org.sedo.satmesh.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import org.sedo.satmesh.databinding.FragmentLoadingBinding;

/**
 * Loading fragment
 */
public class LoadingFragment extends Fragment {

	public LoadingFragment() {
		// Required empty public constructor
	}

	public static LoadingFragment newInstance() {
		return new LoadingFragment();
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
}