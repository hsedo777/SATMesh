package org.sedo.satmesh.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.FragmentImportQrCodeBinding;
import org.sedo.satmesh.ui.vm.ImportQrCodeViewModel;
import org.sedo.satmesh.ui.vm.ViewModelFactory;
import org.sedo.satmesh.utils.Constants;

public class ImportQrCodeFragment extends Fragment {

	public static final String TAG = "ImportQrCodeFragment";

	private FragmentImportQrCodeBinding binding;
	private ImportQrCodeViewModel viewModel;
	// Launcher for image selection from gallery
	private final ActivityResultLauncher<String> pickImageLauncher =
			registerForActivityResult(new ActivityResultContracts.GetContent(),
					new ActivityResultCallback<>() {
						@Override
						public void onActivityResult(Uri uri) {
							Log.d(TAG, "pickImageLauncher.onActivityResult.uri=" + uri);
							if (uri != null) {
								viewModel.decodeFromUri(uri);
							}
						}
					});
	// Launcher for QR code scan using camera
	private final ActivityResultLauncher<ScanOptions> qrScanLauncher =
			registerForActivityResult(new ScanContract(), result -> {
				String content = result.getContents();
				Log.d(TAG, "Content fetched after QR code scan: " + content);
				if (content != null) {
					viewModel.decodeFromCameraResult(content);
				} else {
					Snackbar.make(binding.getRoot(), R.string.no_qr_code_detected, Toast.LENGTH_SHORT).show();
				}
			});
	private AppHomeListener homeListener;

	public ImportQrCodeFragment() {
		// Required default constructor
	}

	/**
	 * Use this factory method to create a new instance of
	 * this fragment using the provided parameters.
	 *
	 * @param hostNodeUuid The UUID of the host node.
	 * @return A new instance of fragment QrCodeFragment.
	 */
	public static ImportQrCodeFragment newInstance(String hostNodeUuid) {
		ImportQrCodeFragment fragment = new ImportQrCodeFragment();
		Bundle args = new Bundle();
		args.putString(Constants.NODE_ADDRESS, hostNodeUuid);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof AppHomeListener) {
			homeListener = (AppHomeListener) context;
		} else {
			throw new RuntimeException("The context must implement AppHomeListener");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		homeListener = null;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		binding = FragmentImportQrCodeBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		viewModel = new ViewModelProvider(this, ViewModelFactory.getInstance(requireActivity().getApplication())).get(ImportQrCodeViewModel.class);
		if (getArguments() != null) {
			viewModel.setHostNodeAddressName(getArguments().getString(Constants.NODE_ADDRESS));
		}

		// Observers
		viewModel.getQrSource().observe(getViewLifecycleOwner(), source -> {
			if (source != null) {
				binding.textQrCodeSource.setText(source);
				binding.qrCodeSourceContainer.setVisibility(View.VISIBLE);
			} else {
				binding.qrCodeSourceContainer.setVisibility(View.GONE);
			}
		});

		viewModel.getPreviewBitmap().observe(getViewLifecycleOwner(), bitmap -> {
			if (bitmap != null) {
				binding.imageQrPreview.setImageBitmap(bitmap);
				binding.imageQrPreview.setVisibility(View.VISIBLE);
			} else {
				binding.imageQrPreview.setVisibility(View.GONE);
			}
		});

		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), message -> {
			if (message != null) {
				Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
				binding.textQrError.setText(message);
				binding.textQrError.setVisibility(View.VISIBLE);
			} else {
				binding.textQrError.setVisibility(View.GONE);
			}
		});

		// Toolbar actions
		binding.toolbarImportQrCode.setOnMenuItemClickListener(this::handleMenuItem);
		requireActivity().getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (homeListener != null) {
					homeListener.backToHome();
				}
			}
		});
	}

	private boolean handleMenuItem(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_import_camera) {
			startCameraScan();
			return true;
		} else if (itemId == R.id.action_import_image) {
			pickImageFromGallery();
			return true;
		} else if (itemId == R.id.action_import_reset) {
			viewModel.reset();
			return true;
		}
		return false;
	}

	// Scan
	private void startCameraScan() {
		ScanOptions options = new ScanOptions();
		options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
		options.setPrompt(getString(R.string.scan_qr_prompt));
		options.setOrientationLocked(false);
		qrScanLauncher.launch(options);
	}

	// Import from gallery
	private void pickImageFromGallery() {
		pickImageLauncher.launch("image/*");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		binding = null;
	}
}
