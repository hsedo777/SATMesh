package org.sedo.satmesh.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.sedo.satmesh.nearby.NearbySignalMessenger;
import org.sedo.satmesh.utils.Constants;

/**
 * BroadcastReceiver to handle "Mark as Read" action from message notifications.
 *
 * @author hsedo777
 */
public class MessageBroadcastReceiver extends BroadcastReceiver {

	private static final String TAG = "MessageBroadcastReceiver";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent != null && Constants.ACTION_BROADCAST_MASSAGE_NOTIFICATION.equals(intent.getAction())) {
			long messageId = intent.getLongExtra(Constants.MESSAGE_ID, -1L);
			long payloadId = intent.getLongExtra(Constants.MESSAGE_PAYLOAD_ID, 0L);
			int notificationId = intent.getIntExtra(Constants.NOTIFICATION_ID, -1);
			String remoteAddressName = intent.getStringExtra(Constants.MESSAGE_SENDER_ADDRESS);
			if (messageId != -1L && payloadId != 0L && remoteAddressName != null) {
				Log.d(TAG, "Received 'Mark as Read' action for message ID: " + messageId);
				try {
					NearbySignalMessenger.getInstance().sendMessageAck(payloadId, remoteAddressName, false, onSuccess -> {
						if (onSuccess) {
							if (notificationId != -1) {
								Intent serviceIntent = new Intent(context, SATMeshCommunicationService.class);
								serviceIntent.putExtras(intent); // Transfer required data from the current intent to service intent
								serviceIntent.setAction(Constants.ACTION_NOTIFICATION_DISMISSED);
								context.startService(serviceIntent);
								Log.d(TAG, "Notification ID " + notificationId + " dismissing request sent to the service.");
							}
						}
					});
				} catch (IllegalStateException e) {
					Log.w(TAG, "Impossible to get 'NearbySignalMessenger' instance.");
				} catch (Exception e) {
					Log.e(TAG, "Unexpected error", e);
				}
			} else {
				Log.w(TAG, "Mark as Read action received without a valid message ID.");
			}
		}
	}
}
