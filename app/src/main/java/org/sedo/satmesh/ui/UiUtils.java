package org.sedo.satmesh.ui;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import org.sedo.satmesh.R;
import org.sedo.satmesh.model.Message;

/**
 * Provides static utility methods for UI-related tasks.
 *
 * @author hsedo777
 */
public class UiUtils {

    private UiUtils() {
    }

    /**
     * Retrieves the drawable resource ID for a given message status.
     * This method maps numerical message status codes to their corresponding
     * visual icons, indicating the message's state (e.g., delivered, pending, failed).
     *
     * @param messageStatus The integer status code of the message,
     *                      as defined by constants in {@link Message}
     *                      class (e.g., {@link Message#MESSAGE_STATUS_DELIVERED}).
     * @return The drawable resource ID corresponding to the message status,
     * or -1 if the status is unknown.
     */
    public static @DrawableRes int getMessageStatusIcon(int messageStatus) {
        switch (messageStatus) {
            case Message.MESSAGE_STATUS_DELIVERED:
                return R.drawable.ic_message_status_delivered;
            case Message.MESSAGE_STATUS_PENDING:
                return R.drawable.ic_message_status_pending;
            case Message.MESSAGE_STATUS_READ:
                return R.drawable.ic_message_status_read;
            case Message.MESSAGE_STATUS_FAILED:
                return R.drawable.ic_message_status_failed;
            case Message.MESSAGE_STATUS_ROUTING:
                return R.drawable.ic_message_status_routing;
            case Message.MESSAGE_STATUS_PENDING_KEY_EXCHANGE:
                return R.drawable.ic_message_status_key_exchange;
            default:
                return -1;
        }
    }

    /**
     * Checks if Bluetooth is currently enabled on the device.
     *
     * @return true if Bluetooth is enabled, false otherwise.
     */
    public static boolean isBluetoothEnabled() {
        try {
            // Get the default BluetoothAdapter.
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

            // If bluetoothAdapter is null, then Bluetooth is not supported on this device.
            // If it's not null, check if it's enabled.
            return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        } catch (Exception e) {
            Log.w("BluetoothUtils", "isBluetoothEnabled:", e);
        }
        return false;
    }

    /**
     * Checks if Wi-Fi is currently active on the device.
     *
     * @param context The application context.
     * @return true if Wi-Fi is enabled, false otherwise.
     */
    public static boolean isWifiEnabled(@NonNull Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            return wifiManager != null && wifiManager.isWifiEnabled();
        } catch (Exception e) {
            Log.w("WifiUtils", "isWifiEnabled:", e);
        }
        return false;
    }
}
