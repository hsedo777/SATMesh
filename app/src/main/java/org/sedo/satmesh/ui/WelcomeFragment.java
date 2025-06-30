package org.sedo.satmesh.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.sedo.satmesh.databinding.FragmentWelcomeBinding;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link WelcomeFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class WelcomeFragment extends Fragment {

	private static final int MAX_LENGTH = 60;
	private WelcomeViewModel viewModel;
	private FragmentWelcomeBinding binding;
	private OnWelcomeCompletedListener welcomeCompletedListener;

	public WelcomeFragment() {
		// Required empty public constructor
	}

	/**
	 * Use this factory method to create a new instance of
	 * this fragment using the provided parameters.
	 *
	 * @return A new instance of fragment WelcomeFragment.
	 */
	public static WelcomeFragment newInstance() {
		return new WelcomeFragment();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		viewModel = new ViewModelProvider(this, ViewModelFactory.getInstance(requireActivity().getApplication())).get(WelcomeViewModel.class);
		// Finish the activity on back key pressed
		requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				requireActivity().finish();
			}
		});
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
	                         Bundle savedInstanceState) {
		// Inflate the layout for this fragment
		binding = FragmentWelcomeBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		binding.nameEditText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
			}

			@Override
			public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
				viewModel.getUserName().postValue(charSequence.toString().trim());
			}

			@Override
			public void afterTextChanged(Editable editable) {
			}
		});

		viewModel.getUserName().observe(getViewLifecycleOwner(),
				e -> binding.continueButton.setEnabled(!e.isEmpty() && e.length() >= 2 && MAX_LENGTH >= e.length()));

		binding.continueButton.setOnClickListener(unused -> welcomeCompletedListener.onWelcomeCompleted(viewModel.getUserName().getValue()));
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof OnWelcomeCompletedListener) {
			welcomeCompletedListener = (OnWelcomeCompletedListener) context;
		} else {
			throw new RuntimeException(context + " doesn't implement `OnWelcomeCompletedListener`");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		welcomeCompletedListener = null;
	}

	public interface OnWelcomeCompletedListener {
		void onWelcomeCompleted(String username);
	}
}