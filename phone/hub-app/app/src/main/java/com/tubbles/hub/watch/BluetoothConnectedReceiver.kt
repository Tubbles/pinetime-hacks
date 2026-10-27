package com.tubbles.hub.watch

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tubbles.hub.work.Scheduling

/**
 * Re-sends the desired watch state when a Bluetooth link comes up, since
 * frames written while the watch was away were dropped. ACL_CONNECTED is
 * exempt from the implicit broadcast limits, so this manifest receiver gets
 * it without a running process, but only with BLUETOOTH_CONNECT granted;
 * without it the periodic poll's resend still covers (doc/DESIGN-hub.md,
 * Leg 2). It fires for any device, which costs one idempotent resend.
 */
class BluetoothConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            Scheduling.enqueueResendAfterConnect(context)
        }
    }
}
