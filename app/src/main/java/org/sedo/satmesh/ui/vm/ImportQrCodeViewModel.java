package org.sedo.satmesh.ui.vm;

import static org.sedo.satmesh.signal.SignalManager.getAddress;
import static org.sedo.satmesh.signal.SignalManager.toCiphertextMessage;

import android.app.Application;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.proto.PersonalInfo;
import org.sedo.satmesh.proto.QrIdentity;
import org.sedo.satmesh.proto.QrMessage;
import org.sedo.satmesh.signal.SignalManager;
import org.sedo.satmesh.ui.data.NodeRepository;
import org.sedo.satmesh.ui.data.NodeRepository.NodeCallback;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.UntrustedIdentityException;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ImportQrCodeViewModel extends AndroidViewModel {

	private static final String TAG = "ImportQrCodeVM";

	private final MutableLiveData<Bitmap> previewBitmap = new MutableLiveData<>();
	private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
	private final MutableLiveData<QrMessage> qrMessageSource = new MutableLiveData<>();
	private final MutableLiveData<Boolean> isTaskOnExecution = new MutableLiveData<>(false);

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final SignalManager signalManager;
	private final NodeRepository nodeRepository;
	private String hostNodeAddressName;

	protected ImportQrCodeViewModel(Application application) {
		super(application);
		signalManager = SignalManager.getInstance(application);
		nodeRepository = new NodeRepository(application);
	}

	public LiveData<Bitmap> getPreviewBitmap() {
		return previewBitmap;
	}

	public LiveData<String> getErrorMessage() {
		return errorMessage;
	}

	public LiveData<QrMessage> getQrMessageSource() {
		return qrMessageSource;
	}

	public void setHostNodeAddressName(String hostNodeAddressName) {
		this.hostNodeAddressName = hostNodeAddressName;
	}

	public MutableLiveData<Boolean> getIsTaskOnExecution() {
		return isTaskOnExecution;
	}

	private void parseQrCode(String base64QrCode) {
		try {
			byte[] qrCodeBytes = android.util.Base64.decode(base64QrCode, android.util.Base64.NO_WRAP);
			QrMessage qrMessage = QrMessage.parseFrom(qrCodeBytes);
			if (qrMessage.getType() == QrMessage.MessageType.PRE_KEY_BUNDLE.getNumber()) {
				if (Objects.equals(qrMessage.getDestinationUuid(), qrMessage.getSourceUuid())) {
					Log.w(TAG, "QR code self targeting, ID=" + qrMessage.getSourceUuid());
					errorMessage.postValue(getApplication().getString(R.string.qr_code_self_targeting));
					return;
				}
				if (!Objects.equals(qrMessage.getDestinationUuid(), hostNodeAddressName)) {
					Log.w(TAG, "PRE_KEY_BUNDLE: QR code stealing, it's originally designated to '" + qrMessage.getDestinationUuid() +
							"' but '" + hostNodeAddressName + "' is decoding it.");
					errorMessage.postValue(getApplication().getString(R.string.qr_code_stealing_use));
					return;
				}
				qrMessageSource.postValue(qrMessage);
			} else if (qrMessage.getType() == QrMessage.MessageType.PERSONAL_INFO.getNumber()) {
				if (!Objects.equals(hostNodeAddressName, qrMessage.getDestinationUuid())) {
					Log.w(TAG, "PERSONAL_INFO: QR code stealing, it's originally designated to '" + qrMessage.getDestinationUuid() +
							"' but '" + hostNodeAddressName + "' is decoding it.");
					errorMessage.postValue(getApplication().getString(R.string.qr_code_stealing_use));
					return;
				}
				qrMessageSource.postValue(qrMessage);
			}
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse QR code", e);
			errorMessage.postValue(getApplication().getString(R.string.qr_code_invalid_content));
		} finally {
			isTaskOnExecution.postValue(false);
		}
	}

	/**
	 * Handle scan result from camera
	 */
	public void decodeFromCameraResult(@NonNull String contents) {
		reset();
		parseQrCode(contents);
	}

	/**
	 * Decode QR code from a given Bitmap.
	 */
	public void decodeFromBitmap(Bitmap bitmap) {
		if (bitmap == null) {
			errorMessage.postValue(getApplication().getString(R.string.no_image_detected));
			return;
		}

		previewBitmap.postValue(bitmap);
		try {
			int width = bitmap.getWidth();
			int height = bitmap.getHeight();
			int[] pixels = new int[width * height];
			bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

			LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
			BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
			Result result = new MultiFormatReader().decode(binaryBitmap);

			parseQrCode(result.getText());
		} catch (Exception e) {
			Log.e(TAG, "Decoding failed", e);
			errorMessage.postValue(getApplication().getString(R.string.qr_code_decode_failed));
		}
	}

	/**
	 * Decode QR code from a Uri (e.g. image from gallery).
	 */
	public void decodeFromUri(@NonNull Uri imageUri) {
		try {
			reset();
			ContentResolver resolver = getApplication().getContentResolver();
			try (InputStream inputStream = resolver.openInputStream(imageUri)) {
				Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
				decodeFromBitmap(bitmap);
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to load image", e);
			errorMessage.postValue(getApplication().getString(R.string.qr_code_import_from_image_failed));
		}
	}

	/**
	 * Reset the current state.
	 */
	public void reset() {
		previewBitmap.postValue(null);
		errorMessage.postValue(null);
		qrMessageSource.postValue(null);
	}

	public void processQrMessage(@NonNull Consumer<Boolean> callback) {
		try {
			QrMessage qrMessage = qrMessageSource.getValue();
			if (qrMessage == null) {
				Log.e(TAG, "QR message is null");
				callback.accept(false);
				return;
			}
			if (qrMessage.getType() == QrMessage.MessageType.PRE_KEY_BUNDLE.getNumber()) {
				executor.execute(() -> {
					handler.post(() -> isTaskOnExecution.postValue(true));
					try {
						QrIdentity identity = QrIdentity.parseFrom(qrMessage.getData());
						signalManager.establishSessionFromRemotePreKeyBundle(
								getAddress(qrMessage.getSourceUuid()),
								signalManager.deserializePreKeyBundle(identity.getPreKeyBundle().toByteArray()));
						Log.d(TAG, "Signal secure session established successfully by '" +
								hostNodeAddressName + "' with '" + qrMessage.getSourceUuid() + "'.");
						handler.post(() -> callback.accept(true));
					} catch (InvalidProtocolBufferException e) {
						Log.e(TAG, "The identity data may have been malformed.", e);
						handler.post(() -> {
							errorMessage.postValue(getApplication().getString(R.string.qr_code_invalid_content));
							callback.accept(false);
						});
					} catch (InvalidKeyException | UntrustedIdentityException e) {
						Log.e(TAG, "Failed to establish the Signal secure session.", e);
						handler.post(() -> {
							errorMessage.postValue(getApplication().getString(R.string.qr_code_secure_session_failed));
							callback.accept(false);
						});
					} catch (Exception e) {
						Log.e(TAG, "Failed to establish the Signal secure session.", e);
						handler.post(() -> {
							errorMessage.postValue(getApplication().getString(R.string.qr_code_process_failed));
							callback.accept(false);
						});
					} finally {
						handler.post(() -> isTaskOnExecution.postValue(false));
					}
				});
			} else if (qrMessage.getType() == QrMessage.MessageType.PERSONAL_INFO.getNumber()) {
				executor.execute(() -> {
					handler.post(() -> isTaskOnExecution.postValue(true));
					try {
						byte[] decryptedData = signalManager.decryptMessage(
								getAddress(qrMessage.getSourceUuid()),
								toCiphertextMessage(qrMessage.getData().toByteArray()));
						PersonalInfo info = PersonalInfo.parseFrom(decryptedData);
						Log.d(TAG, "Personal information decrypted successfully.");
						nodeRepository.findOrCreateNodeAsync(info.getAddressName(), new NodeCallback() {
							@Override
							public void onNodeReady(@NonNull Node node) {
								Log.d(TAG, "Node found or created successfully.");
								node.setPersonalInfo(info);
								nodeRepository.update(node, success -> {
									if (success) {
										handler.post(() -> {
											isTaskOnExecution.postValue(false);
											callback.accept(true);
										});
									} else {
										handler.post(() -> {
											ImportQrCodeViewModel.this.errorMessage.postValue(getApplication().getString(R.string.qr_code_process_failed));
											isTaskOnExecution.postValue(false);
											callback.accept(false);
										});
									}
								});
							}

							@Override
							public void onError(@NonNull String errorMessage) {
								Log.e(TAG, "Failed to decrypt the personal information: " + errorMessage);
								handler.post(() -> {
									ImportQrCodeViewModel.this.errorMessage.postValue(getApplication().getString(R.string.qr_code_process_failed));
									isTaskOnExecution.postValue(false);
									callback.accept(true);
								});
							}
						});
					} catch (Exception e) {
						Log.e(TAG, "Failed to decrypt the personal information.", e);
						handler.post(() -> {
							errorMessage.postValue(getApplication().getString(R.string.qr_code_invalid_content));
							isTaskOnExecution.postValue(false);
							callback.accept(false);
						});
					}
				});
			} else {
				Log.e(TAG, "Unknown QR message type: " + qrMessage.getType());
				callback.accept(false);
			}
		} catch (Exception e) {
			errorMessage.postValue(getApplication().getString(R.string.qr_code_secure_session_failed));
			callback.accept(false);
			Log.w(TAG, "Failed to established the Signal secure session.", e);
		}
	}

	public void retrieveNodeAsync(String addressName, Consumer<Node> onReady) {
		try {
			Node node = nodeRepository.findNodeSync(addressName);
			handler.post(() -> onReady.accept(node));
		} catch (Exception e) {
			handler.post(() -> onReady.accept(null));
		}
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		executor.shutdown();
	}
}
