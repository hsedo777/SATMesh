package org.sedo.satmesh.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentQrCodeBinding;
import org.sedo.satmesh.ui.vm.QrCodeViewModel;
import org.sedo.satmesh.ui.vm.ViewModelFactory;
import org.sedo.satmesh.utils.Constants;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link QrCodeFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class QrCodeFragment extends Fragment {

	public static final String TAG = "QrCodeFragment";

	private FragmentQrCodeBinding binding;
	private QrCodeViewModel viewModel;
	private AppHomeListener homeListener;

	public QrCodeFragment() {
		// Required empty public constructor
	}

	public static QrCodeFragment newInstance(String hostNodeUuid) {
		QrCodeFragment fragment = new QrCodeFragment();
		Bundle args = new Bundle();
		args.putString(Constants.NODE_ADDRESS, hostNodeUuid);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		viewModel = new ViewModelProvider(this, ViewModelFactory.getInstance(requireActivity().getApplication())).get(QrCodeViewModel.class);
		if (getArguments() != null) {
			String hostNodeUuid = getArguments().getString(Constants.NODE_ADDRESS);
			viewModel.setHostNodeUuid(hostNodeUuid);
		}
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof AppHomeListener) {
			homeListener = (AppHomeListener) context;
		} else {
			throw new RuntimeException("The context require to implement interface `AppHomeListener`.");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		homeListener = null;
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		binding = FragmentQrCodeBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		// Observers
		viewModel.getQrCodeBitmap().observe(getViewLifecycleOwner(), bitmap -> {
			binding.qrCodeImage.setImageBitmap(bitmap);
			boolean alive = bitmap != null;
			binding.qrCodeImage.setVisibility(alive ? View.VISIBLE : View.GONE);
			setMenuItemEnabled(alive);
		});

		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), message -> {
			if (message != null) {
				Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
				binding.textError.setText(message);
				binding.textError.setVisibility(View.VISIBLE);
			} else {
				binding.textError.setVisibility(View.GONE);
			}
		});

		viewModel.getDownloadMessage().observe(getViewLifecycleOwner(), holder -> {
			if (holder != null) {
				Snackbar snackbar = Snackbar.make(binding.getRoot(), holder.getFirst(), Snackbar.LENGTH_LONG);
				if (holder.getSecond()){
					snackbar.setAction(R.string.open, v -> {
						Intent intent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
						intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						startActivity(intent);
					});
				}
				snackbar.show();
			}
		});

		viewModel.getUuidInput().observe(getViewLifecycleOwner(), uuid -> {
			// If UUID changes then the current QR code is no longer valid
			viewModel.clearQrCode();
		});

		binding.editTextUuid.addTextChangedListener(new TextWatcher() {
			@Override
			public void afterTextChanged(Editable s) {
			}

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				viewModel.setUuidInput(s.toString());
			}
		});

		// Actions UI
		binding.buttonGenerateQr.setOnClickListener(v -> {
			// Hide keyboard
			InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
			View focus;
			if (imm != null && (focus = requireActivity().getCurrentFocus()) != null) {
				imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
			}
			viewModel.generateQrCode();
		});

		binding.qrCodeToolbar.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == R.id.action_download) {
				viewModel.saveQrCodeToGallery();
				return true;
			}
			if (item.getItemId() == R.id.action_share) {
				//viewModel.shareImage();
				return true;
			}
			if (item.getItemId() == R.id.action_reset) {
				viewModel.clearQrCode();
				return true;
			}

			return false;
		});

		requireActivity().getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (homeListener != null) {
					homeListener.backToHome();
				}
			}
		});
	}

	private void setMenuItemEnabled(boolean enabled) {
		Menu menu = binding.qrCodeToolbar.getMenu();
		menu.findItem(R.id.action_download).setEnabled(enabled);
		menu.findItem(R.id.action_reset).setEnabled(enabled);
		menu.findItem(R.id.action_share).setEnabled(enabled);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		binding = null;
	}
}
