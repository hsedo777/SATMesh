package org.sedo.satmesh.ui.vm;

import static org.sedo.satmesh.utils.Constants.NODE_ADDRESS_NAME_PREFIX;
import static org.sedo.satmesh.utils.Constants.QR_CODE_BITMAP_HEIGHT;
import static org.sedo.satmesh.utils.Constants.QR_CODE_BITMAP_WIDTH;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.protobuf.ByteString;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.sedo.satmesh.R;
import org.sedo.satmesh.proto.QRIdentity;
import org.sedo.satmesh.signal.SignalManager;
import org.whispersystems.libsignal.state.PreKeyBundle;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class QrCodeViewModel extends AndroidViewModel {

	private static final String TAG = "QrCodeViewModel";

	private final MutableLiveData<String> uuidInput = new MutableLiveData<>();
	private final MutableLiveData<Bitmap> qrCodeBitmap = new MutableLiveData<>();
	private final MutableLiveData<String> errorMessage = new MutableLiveData<>();

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final SignalManager signalManager;
	private String hostNodeUuid;

	// Default constructor
	protected QrCodeViewModel(@NonNull Application application) {
		super(application);
		signalManager = SignalManager.getInstance(application);
	}

	// LiveData
	public LiveData<String> getUuidInput() {
		return uuidInput;
	}

	public void setUuidInput(String uuid) {
		String trimmed = uuid != null ? uuid.trim() : "";
		if (!trimmed.equals(uuidInput.getValue())) {
			uuidInput.setValue(trimmed);
		}
	}

	public LiveData<Bitmap> getQrCodeBitmap() {
		return qrCodeBitmap;
	}

	public LiveData<String> getErrorMessage() {
		return errorMessage;
	}

	public void setHostNodeUuid(String hostNodeUuid) {
		this.hostNodeUuid = hostNodeUuid;
	}

	// QR code generation
	public void generateQrCode() {
		// Generate only if the target address has changed
		if (getCurrentQrCodeBitmap() != null) {
			Log.d(TAG, "QR code already generated for UUID: " + uuidInput.getValue());
			return;
		}
		Log.d(TAG, "Generating QR code for UUID: " + uuidInput.getValue());
		String uuid = uuidInput.getValue();
		if (hostNodeUuid == null || !validateUuid(uuid)) {
			Log.d(TAG, "Invalid UUID: " + uuid);
			qrCodeBitmap.setValue(null);
			errorMessage.setValue(getApplication().getString(R.string.invalid_uuid));
			return;
		}

		errorMessage.setValue(null);
		serializeIdentityAsync(uuid, data -> {
			// On main thread
			if (data == null) {
				Log.w(TAG, "Failed to serialize identity for UUID: " + uuid);
				errorMessage.setValue(getApplication().getString(R.string.qr_code_generation_failed));
				return;
			}
			Bitmap bitmap = generateQrBitmapFromData(data);
			qrCodeBitmap.setValue(bitmap);
			if (bitmap == null) {
				Log.w(TAG, "Failed to generate QR code for UUID: " + uuid);
				errorMessage.setValue(getApplication().getString(R.string.qr_code_generation_failed));
			}
		});
	}

	// UUID format validation
	public boolean validateUuid(String uuid) {
		if (uuid == null || uuid.isEmpty()) return false;
		if (!uuid.startsWith(NODE_ADDRESS_NAME_PREFIX)) return false;
		try {
			UUID.fromString(uuid.substring(NODE_ADDRESS_NAME_PREFIX.length()));
			return true;
		} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
			return false;
		}
	}

	// Identity serialization
	private void serializeIdentityAsync(String destinationUuid, Consumer<String> callback) {
		executor.execute(() -> {
			try {
				PreKeyBundle localPreKeyBundle = signalManager.generateOurPreKeyBundle();
				byte[] serializedPreKeyBundle = signalManager.serializePreKeyBundle(localPreKeyBundle);

				QRIdentity identity = QRIdentity.newBuilder()
						.setSourceUuid(hostNodeUuid)
						.setDestinationUuid(destinationUuid)
						.setPreKeyBundle(ByteString.copyFrom(serializedPreKeyBundle))
						.build();

				byte[] identityBytes = identity.toByteArray();
				String base64 = android.util.Base64.encodeToString(identityBytes, android.util.Base64.NO_WRAP);
				Log.d(TAG, "Serialized identity: " + base64);
				// Clear data from memory
				Arrays.fill(serializedPreKeyBundle, (byte) 0);
				Arrays.fill(identityBytes, (byte) 0);


				handler.post(() -> callback.accept(base64));

			} catch (Exception e) {
				Log.e(TAG, "Error serializing identity", e);
				handler.post(() -> callback.accept(null));
			}
		});
	}

	private Bitmap generateQrBitmapFromData(String data) {
		QRCodeWriter writer = new QRCodeWriter();
		try {
			BitMatrix bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, QR_CODE_BITMAP_WIDTH, QR_CODE_BITMAP_HEIGHT);
			Bitmap bitmap = Bitmap.createBitmap(QR_CODE_BITMAP_WIDTH, QR_CODE_BITMAP_HEIGHT, Bitmap.Config.RGB_565);
			for (int x = 0; x < QR_CODE_BITMAP_WIDTH; x++) {
				for (int y = 0; y < QR_CODE_BITMAP_HEIGHT; y++) {
					bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
				}
			}
			return bitmap;
		} catch (WriterException e) {
			Log.e(TAG, "Error generating QR code", e);
			return null;
		}
	}

	public void clearQrCode() {
		qrCodeBitmap.setValue(null);
		errorMessage.setValue(null);
	}

	public Bitmap getCurrentQrCodeBitmap() {
		return qrCodeBitmap.getValue();
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		executor.shutdown();
	}
}
