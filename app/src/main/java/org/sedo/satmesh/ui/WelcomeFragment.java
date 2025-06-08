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

	private WelcomeViewModel viewModel;
	private FragmentWelcomeBinding binding;
	private OnWelcomeCompletedListener listener;


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
		viewModel = new ViewModelProvider(this, new ViewModelFactory(requireActivity().getApplication())).get(WelcomeViewModel.class);
		// Finish the activity on back key pressed
		requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				WelcomeFragment.this.requireActivity().finish();
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
			public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

			@Override
			public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
				viewModel.onUserNameChanged(charSequence.toString());
			}

			@Override
			public void afterTextChanged(Editable editable) {}
		});

		viewModel.observe(getViewLifecycleOwner(), e -> binding.continueButton.setEnabled(e != null && e));

		binding.continueButton.setOnClickListener(unused -> listener.onWelcomeCompleted(viewModel.getUserName()));
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof OnWelcomeCompletedListener){
			listener = (OnWelcomeCompletedListener)context;
		} else {
			throw new RuntimeException(context + " doesn't implement `OnWelcomeCompletedListener`");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		listener = null;
	}

	public interface OnWelcomeCompletedListener{
		void onWelcomeCompleted(String username);
	}
}