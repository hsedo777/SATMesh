package org.sedo.satmesh.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

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
import org.sedo.satmesh.proto.QrMessage;
import org.sedo.satmesh.ui.vm.ImportQrCodeViewModel;
import org.sedo.satmesh.ui.vm.ViewModelFactory;
import org.sedo.satmesh.utils.Constants;

import java.util.Objects;

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
					Snackbar.make(binding.getRoot(), R.string.no_qr_code_detected, Snackbar.LENGTH_SHORT).show();
				}
			});

	// Listeners
	private AppHomeListener homeListener;
	private QrCodeGenerationListener qrCodeGenerationListener;
	private DiscussionListener discussionListener;

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
		if (context instanceof AppHomeListener && context instanceof QrCodeGenerationListener &&
				context instanceof DiscussionListener) {
			homeListener = (AppHomeListener) context;
			qrCodeGenerationListener = (QrCodeGenerationListener) context;
			discussionListener = (DiscussionListener) context;
		} else {
			throw new RuntimeException("The context must implement `AppHomeListener`," +
					" `QrCodeGenerationListener` and `DiscussionListener`");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		homeListener = null;
		qrCodeGenerationListener = null;
		discussionListener = null;
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
		setupObservers();

		// Click events
		setupClickEvent();

		// Toolbar actions
		binding.toolbarImportQrCode.setOnMenuItemClickListener(this::handleMenuItem);
		binding.toolbarImportQrCode.setNavigationOnClickListener(v -> {
			Log.d(TAG, "NavigationOnClickListener: homeListener=" + homeListener);
			if (homeListener != null) {
				homeListener.backToHome();
			}
		});
		requireActivity().getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				Log.d(TAG, "handleOnBackPressed: homeListener=" + homeListener);
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

	private void setupObservers() {
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
				binding.qrCodeActionButton.setEnabled(false);
			} else {
				binding.textQrError.setVisibility(View.GONE);
			}
		});

		viewModel.getQrMessageSource().observe(getViewLifecycleOwner(), qrMessage -> {
			binding.qrCodeActionButton.setEnabled(qrMessage != null);
			if (qrMessage != null) {
				binding.textQrCodeSource.setText(qrMessage.getSourceUuid());
				binding.qrCodeSourceContainer.setVisibility(View.VISIBLE);
			} else {
				binding.qrCodeSourceContainer.setVisibility(View.GONE);
			}
		});

		viewModel.getIsTaskOnExecution().observe(getViewLifecycleOwner(),
				isOnExecution -> binding.importQrProgressBar.setVisibility(isOnExecution ? View.VISIBLE : View.GONE));
	}

	private void setupClickEvent() {
		binding.textQrCodeSource.setOnClickListener(v -> {
			QrMessage qrMessage = viewModel.getQrMessageSource().getValue();
			String uuid = qrMessage == null ? null : qrMessage.getSourceUuid();
			if (uuid != null) {
				if (UiUtils.copyToClipboard(requireContext(), uuid, getString(R.string.qr_code_source_label))) {
					Snackbar.make(binding.getRoot(), R.string.qr_code_source_copied, Snackbar.LENGTH_SHORT).show();
				}
			}
		});

		binding.qrCodeActionButton.setOnClickListener(v -> viewModel.processQrMessage(success -> {
			if (success) {
				// On success, the `QrMessage` exists
				QrMessage qrMessage = Objects.requireNonNull(viewModel.getQrMessageSource().getValue());
				if (qrMessage.getType() == QrMessage.MessageType.PRE_KEY_BUNDLE.getNumber()) {
					if (qrCodeGenerationListener != null) {
						qrCodeGenerationListener.generatedAcknowledgeTo(qrMessage.getSourceUuid());
					}
				} else if (qrMessage.getType() == QrMessage.MessageType.PERSONAL_INFO.getNumber()) {
					viewModel.retrieveNodeAsync(qrMessage.getSourceUuid(), node -> {
						if (discussionListener != null && node != null) {
							discussionListener.discussWith(node, true);
						} else {
							Log.w(TAG, "Failed to init discussion with the node: " + node);
						}
					});
				}
			} else if (viewModel.getErrorMessage().getValue() == null) {
				Snackbar.make(binding.getRoot(), R.string.qr_code_process_failed, Snackbar.LENGTH_SHORT).show();
			}
			// Else, an error message is displayed.
		}));
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		binding = null;
	}
}
