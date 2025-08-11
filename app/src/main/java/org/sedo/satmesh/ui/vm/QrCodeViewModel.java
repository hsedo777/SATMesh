package org.sedo.satmesh.ui.vm;

import static org.sedo.satmesh.utils.Constants.NODE_ADDRESS_NAME_PREFIX;
import static org.sedo.satmesh.utils.Constants.QR_CODE_BITMAP_HEIGHT;
import static org.sedo.satmesh.utils.Constants.QR_CODE_BITMAP_QUALITY;
import static org.sedo.satmesh.utils.Constants.QR_CODE_BITMAP_WIDTH;
import static org.sedo.satmesh.utils.Constants.QR_CODE_IMAGE_MIME_TYPE;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
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
import org.sedo.satmesh.utils.BiObjectHolder;
import org.sedo.satmesh.utils.Constants;
import org.whispersystems.libsignal.state.PreKeyBundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * ViewModel for managing QR code generation and display.
 * This ViewModel handles the logic for creating QR codes that contain identity information
 * for establishing secure communication channels remotely.
 *
 * @author hsedo777
 */
public class QrCodeViewModel extends AndroidViewModel {

	private static final String TAG = "QrCodeViewModel";

	private final MutableLiveData<String> uuidInput = new MutableLiveData<>();
	private final MutableLiveData<Bitmap> qrCodeBitmap = new MutableLiveData<>();
	private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
	private final MutableLiveData<BiObjectHolder<String, Boolean>> downloadMessage = new MutableLiveData<>();

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final SignalManager signalManager;
	private String hostNodeUuid;

	/**
	 * Constructs a new QrCodeViewModel.
	 *
	 * @param application The application context.
	 */
	protected QrCodeViewModel(@NonNull Application application) {
		super(application);
		signalManager = SignalManager.getInstance(application);
	}

	/**
	 * Gets the LiveData for the UUID input by the user.
	 *
	 * @return A {@code LiveData} representing the UUID input.
	 */
	public LiveData<String> getUuidInput() {
		return uuidInput;
	}

	/**
	 * Sets the UUID input. Trims the input and updates the LiveData
	 * only if the new value is different from the current one.
	 *
	 * @param uuid The UUID string to set.
	 */
	public void setUuidInput(String uuid) {
		String trimmed = uuid != null ? uuid.trim() : "";
		if (!trimmed.equals(uuidInput.getValue())) {
			uuidInput.setValue(trimmed);
		}
	}

	/**
	 * Gets the LiveData for the generated QR code bitmap.
	 *
	 * @return A {@code LiveData} representing the QR code bitmap.
	 */
	public LiveData<Bitmap> getQrCodeBitmap() {
		return qrCodeBitmap;
	}

	/**
	 * Gets the LiveData for error messages.
	 *
	 * @return A {@code LiveData} representing error messages.
	 */
	public LiveData<String> getErrorMessage() {
		return errorMessage;
	}

	/**
	 * Gets the LiveData for information messages.
	 *
	 * @return A {@code LiveData} representing information messages.
	 */
	public LiveData<BiObjectHolder<String, Boolean>> getDownloadMessage() {
		return downloadMessage;
	}

	/**
	 * Sets the UUID of the host node. This is used as the source UUID in the QR code identity.
	 *
	 * @param hostNodeUuid The UUID of the host node.
	 */
	public void setHostNodeUuid(String hostNodeUuid) {
		this.hostNodeUuid = hostNodeUuid;
	}

	/**
	 * Generates a QR code based on the current UUID input.
	 * If a QR code has already been generated for the current UUID, this method does nothing.
	 * Validates the UUID and then asynchronously serializes the identity and generates the QR bitmap.
	 * Updates {@code LiveData} with the generated bitmap or an error message.
	 */
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

	/**
	 * Validates the format of a given UUID string.
	 * The UUID must not be null or empty, must start with {@link Constants#NODE_ADDRESS_NAME_PREFIX},
	 * and the remaining part must be a valid UUID.
	 *
	 * @param uuid The UUID string to validate.
	 * @return True if the UUID is valid, false otherwise.
	 */
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

	/**
	 * Asynchronously serializes the identity information into a Base64 string.
	 * This includes the source (host) UUID, destination UUID, and the host's PreKeyBundle.
	 *
	 * @param destinationUuid The UUID of the destination node.
	 * @param callback        A consumer that accepts the Base64 encoded identity string, or null on failure.
	 */
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

	/**
	 * Generates a QR code bitmap from the given data string.
	 *
	 * @param data The string data to encode in the QR code.
	 * @return A Bitmap representing the QR code, or null if generation fails.
	 */
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

	/**
	 * Clears the currently displayed QR code and any error messages.
	 */
	public void clearQrCode() {
		qrCodeBitmap.setValue(null);
		errorMessage.setValue(null);
	}

	/**
	 * Gets the current QR code bitmap.
	 *
	 * @return The current Bitmap of the QR code, or null if none is generated.
	 */
	public Bitmap getCurrentQrCodeBitmap() {
		return qrCodeBitmap.getValue();
	}

	/**
	 * Called when the ViewModel is no longer used and will be destroyed.
	 * Shuts down the executor service.
	 */
	@Override
	protected void onCleared() {
		super.onCleared();
		executor.shutdown();
	}

	/**
	 * Saves the current QR code bitmap to the device's image gallery.
	 * If no bitmap is currently active, this method does nothing.
	 */
	public void saveQrCodeToGallery() {
		Bitmap bitmap = getCurrentQrCodeBitmap();
		if (bitmap == null) {
			Log.w(TAG, "There is no active bitmap at this time.");
			return;
		}
		Context context = getApplication();
		String filename = Constants.QR_CODE_IMAGE_EXPORT_NAME;
		ContentValues values = new ContentValues();
		values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
		values.put(MediaStore.Images.Media.MIME_TYPE, QR_CODE_IMAGE_MIME_TYPE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			values.put(MediaStore.Images.Media.IS_PENDING, 1);
		}

		Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
		if (uri == null) {
			Log.e(TAG, "Failed to create new MediaStore record.");
			return;
		}
		BiObjectHolder<String, Boolean> holder = new BiObjectHolder<>();
		try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
			bitmap.compress(Bitmap.CompressFormat.PNG, QR_CODE_BITMAP_QUALITY, Objects.requireNonNull(out));
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				values.clear();
				values.put(MediaStore.Images.Media.IS_PENDING, 0);
				context.getContentResolver().update(uri, values, null, null);
			}
			Log.d(TAG, "QR code saved to gallery.");
			holder.post(context.getString(R.string.qr_code_download_success, filename), true);
		} catch (Exception e) {
			Log.e(TAG, "Failed to save the QR code.", e);
			holder.post(context.getString(R.string.qr_code_download_failed), false);
		}
		downloadMessage.postValue(holder);
	}

	public Intent shareQrCode() {
		Bitmap bitmap = getCurrentQrCodeBitmap();
		if (bitmap == null) {
			Log.w(TAG, "No QR code available to share.");
			return null;
		}

		try {
			Context context = getApplication();
			String filename = Constants.QR_CODE_IMAGE_EXPORT_NAME;
			// 1. Save file temporary in `cache/`
			File cachePath = new File(context.getCacheDir(), Constants.QR_CODE_ROOT_IN_CACHE);
			if (!cachePath.exists()) {
				Log.d(TAG, "Attempt to create QR code root directory in cache dir: " + cachePath.mkdirs());
			}

			File file = new File(cachePath, filename);
			if (file.exists()) {
				Log.d(TAG, "File already exists. Delete it first: " + file.delete());
			}
			try (FileOutputStream fos = new FileOutputStream(file)) {
				// Process saving
				bitmap.compress(Bitmap.CompressFormat.PNG, QR_CODE_BITMAP_QUALITY, fos);
			}

			// 2. Create an URI to the tmp file via FileProvider
			Uri contentUri = FileProvider.getUriForFile(
					context,
					context.getPackageName() + "." + Constants.QR_CODE_ROOT_IN_PROVIDER,
					file
			);

			// 3. Create Intent of sharing
			Intent shareIntent = new Intent(Intent.ACTION_SEND);
			shareIntent.setType(QR_CODE_IMAGE_MIME_TYPE);
			shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
			shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

			return shareIntent;

		} catch (IOException e) {
			Log.e(TAG, "Error preparing QR code for sharing", e);
			return null;
		}
	}
}
