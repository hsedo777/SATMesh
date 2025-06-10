package org.sedo.satmesh.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Receive user device boot completed event and launch the the communication
 * service
 * @see SATMeshCommunicationService
 */
public class BootReceiver extends BroadcastReceiver {

	private static final String TAG = "BootReceiver";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
			Log.d(TAG, "Boot completed broadcast received. Starting SATMeshCommunicationService.");

			// Create an explicit intent for the communication service
			Intent serviceIntent = new Intent(context, SATMeshCommunicationService.class);
			serviceIntent.setAction(SATMeshCommunicationService.ACTION_START_FOREGROUND_SERVICE);

			// Start the service as a foreground service
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				// For Android 8.0 (Oreo) and above, use startForegroundService()
				// This ensures the service has enough time to call startForeground()
				context.startForegroundService(serviceIntent);
			} else {
				// For older versions, use startService()
				context.startService(serviceIntent);
			}
		}
	}
}
