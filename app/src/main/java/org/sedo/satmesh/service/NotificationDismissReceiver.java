package org.sedo.satmesh.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.sedo.satmesh.utils.Constants;

public class NotificationDismissReceiver extends BroadcastReceiver {

	private static final String TAG = "NotificationDismissReceiver";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent != null && Constants.ACTION_NOTIFICATION_DISMISSED.equals(intent.getAction())) {
			Log.d(TAG, "Transfer notification dismiss handling to SATMeshCommunicationService");
			Intent serviceIntent = new Intent(context, SATMeshCommunicationService.class);
			serviceIntent.setAction(Constants.ACTION_NOTIFICATION_DISMISSED);
			serviceIntent.putExtras(intent);
			context.startService(serviceIntent);
		}
	}
}
