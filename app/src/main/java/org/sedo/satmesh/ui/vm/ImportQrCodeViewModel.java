package org.sedo.satmesh.ui.vm;

import android.app.Application;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
import org.sedo.satmesh.proto.QRIdentity;

import java.io.InputStream;
import java.util.Objects;

public class ImportQrCodeViewModel extends AndroidViewModel {

	private static final String TAG = "ImportQrCodeVM";

	private final MutableLiveData<Bitmap> previewBitmap = new MutableLiveData<>();
	private final MutableLiveData<String> qrSource = new MutableLiveData<>();
	private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
	private String hostNodeAddressName;

	protected ImportQrCodeViewModel(Application application) {
		super(application);
	}

	public LiveData<Bitmap> getPreviewBitmap() {
		return previewBitmap;
	}

	public LiveData<String> getQrSource() {
		return qrSource;
	}

	public LiveData<String> getErrorMessage() {
		return errorMessage;
	}

	public void setHostNodeAddressName(String hostNodeAddressName) {
		this.hostNodeAddressName = hostNodeAddressName;
	}

	private void parseQrCode(String base64QrCode) {
		try {
			byte[] qrCodeBytes = android.util.Base64.decode(base64QrCode, android.util.Base64.NO_WRAP);
			QRIdentity identity = QRIdentity.parseFrom(qrCodeBytes);
			qrSource.setValue(identity.getSourceUuid());
			if (Objects.equals(identity.getDestinationUuid(), identity.getSourceUuid())) {
				Log.w(TAG, "QR code self targeting, ID=" + identity.getSourceUuid());
				errorMessage.setValue(getApplication().getString(R.string.qr_code_self_targeting));
				return;
			}
			if (!Objects.equals(identity.getDestinationUuid(), hostNodeAddressName)) {
				Log.w(TAG, "QR code stealing, it's originally designated to '" + identity.getDestinationUuid() +
						"' but '" + hostNodeAddressName + "' is decoding it.");
				errorMessage.setValue(getApplication().getString(R.string.qr_code_stealing_use));
				return;
			}
			errorMessage.setValue(null);
		} catch (InvalidProtocolBufferException e) {
			Log.e(TAG, "Failed to parse QR code", e);
			errorMessage.setValue(getApplication().getString(R.string.qr_code_invalid_content));
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
			errorMessage.setValue(getApplication().getString(R.string.no_image_detected));
			return;
		}

		previewBitmap.setValue(bitmap);
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
			qrSource.setValue(null);
			errorMessage.setValue(getApplication().getString(R.string.qr_code_decode_failed));
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
			errorMessage.setValue(getApplication().getString(R.string.qr_code_import_from_image_failed));
		}
	}

	/**
	 * Reset the current state.
	 */
	public void reset() {
		previewBitmap.setValue(null);
		qrSource.setValue(null);
		errorMessage.setValue(null);
	}
}
