/*
 * Receives watch -> phone state notifications rebroadcast by Gadgetbridge's BLE
 * Intent API and applies them to DataModel via ClockSyncBridge.
 *
 * Gadgetbridge broadcasts CHARACTERISTIC_CHANGED with:
 *   EXTRA_DEVICE_ADDRESS  - the watch MAC
 *   EXTRA_CHARACTERISTIC  - the characteristic UUID (NOTE: not EXTRA_CHARACTERISTIC_UUID,
 *                           which is the write-command key; verified in BleIntentApi.java)
 *   EXTRA_PAYLOAD         - the value as a hex string
 *
 * Delivery caveat: Gadgetbridge only sets an explicit target package on this
 * broadcast when the device's BLE-API package filter is configured. A
 * manifest-declared receiver (this class) only receives implicit broadcasts on
 * Android 8+ when they are package-targeted, so the user MUST set that package
 * filter to the fork's package (see README). Otherwise the broadcast is dropped.
 *
 * Frames are only applied when EXTRA_DEVICE_ADDRESS matches the configured
 * watch MAC, so a second Gadgetbridge-managed device cannot drive the clock.
 * Note the receiver is exported and broadcasts are unsigned, so any local app
 * that knows the MAC could still forge frames; worst case is a nuisance
 * start/stop (accepted v1 risk, see README).
 */
package com.android.deskclock.clocksync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

public final class ClockSyncReceiver extends BroadcastReceiver {

    private static final String TAG = "ClockSyncReceiver";

    // Gadgetbridge CHARACTERISTIC_CHANGED extras (BleIntentApi.java).
    private static final String EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS";
    private static final String EXTRA_CHARACTERISTIC = "EXTRA_CHARACTERISTIC";
    private static final String EXTRA_PAYLOAD = "EXTRA_PAYLOAD";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
        if (!ClockSyncBridge.getInstance().isConfiguredWatch(deviceAddress)) {
            return; // no watch configured, or the frame is from another device
        }

        final String characteristic = intent.getStringExtra(EXTRA_CHARACTERISTIC);
        if (ClockSyncBridge.KEYTONES_CHARACTERISTIC_UUID.equalsIgnoreCase(characteristic)) {
            // Watch DTMF key press: hand it to the dialer fork (this app is
            // the single Gadgetbridge Intent API target, so it plays hub).
            ClockSyncBridge.getInstance().forwardKeyTone(intent);
            return;
        }
        if (!ClockSyncBridge.STATE_CHARACTERISTIC_UUID.equalsIgnoreCase(characteristic)) {
            return; // not the ClockSync state characteristic
        }

        final String payloadHex = intent.getStringExtra(EXTRA_PAYLOAD);
        if (TextUtils.isEmpty(payloadHex)) {
            return;
        }

        final ClockSyncFrame frame;
        try {
            frame = ClockSyncFrame.decode(ClockSyncFrame.hexToBytes(payloadHex));
        } catch (IllegalArgumentException malformed) {
            Log.w(TAG, "dropping malformed ClockSync payload: " + payloadHex, malformed);
            return;
        }

        ClockSyncBridge.getInstance().onRemoteFrame(frame);
    }
}
