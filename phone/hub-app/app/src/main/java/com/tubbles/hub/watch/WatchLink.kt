package com.tubbles.hub.watch

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.tubbles.hub.BuildConfig
import com.tubbles.hub.StatusStore

/**
 * Writes event frames to the watch through Gadgetbridge's BLE Intent API,
 * the same contract as the ClockSync bridge and Phone T
 * (doc/DESIGN-hub.md, Leg 2).
 *
 * The Intent API has no delivery queue: a write while the watch is away or
 * not yet initialized is dropped. So the last frame per source (clears
 * included) is kept as the desired watch state and [resendAll] repeats all
 * of it. The watch's event table is keyed by source, so a repeat is
 * harmless.
 */
class WatchLink(context: Context) {
    private val applicationContext = context.applicationContext
    private val desiredState =
        applicationContext.getSharedPreferences(DESIRED_STATE_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val statusStore = StatusStore(applicationContext)

    /** Remembers [frame] as what the watch should hold for [sourceId]. */
    fun store(sourceId: Int, frame: ByteArray) {
        desiredState.edit { putString(sourceId.toString(), EventFrame.toHex(frame)) }
    }

    fun send(frame: ByteArray) {
        sendHex(EventFrame.toHex(frame))
    }

    fun resendAll() {
        storedFrames().values.forEach { frameHex -> sendHex(frameHex) }
    }

    /** The desired watch state as hex frames, by source id. */
    fun storedFrames(): Map<Int, String> =
        desiredState.all
            .map { (key, value) -> key.toInt() to value.toString() }
            .sortedBy { (sourceId, _) -> sourceId }
            .toMap()

    private fun sendHex(frameHex: String) {
        val intent = Intent(ACTION_CHARACTERISTIC_WRITE)
            .setPackage(BuildConfig.GADGETBRIDGE_PACKAGE)
            .putExtra(EXTRA_DEVICE_ADDRESS, BuildConfig.WATCH_MAC)
            .putExtra(EXTRA_CHARACTERISTIC_UUID, EVENTS_CHARACTERISTIC_UUID)
            .putExtra(EXTRA_PAYLOAD, frameHex)
        applicationContext.sendBroadcast(intent)
        statusStore.recordSend()
    }

    private companion object {
        // Gadgetbridge BLE Intent API contract, as in
        // phone/clocksync/src/com/android/deskclock/clocksync/ClockSyncBridge.java.
        const val ACTION_CHARACTERISTIC_WRITE =
            "nodomain.freeyourgadget.gadgetbridge.ble_api.commands.CHARACTERISTIC_WRITE"
        const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"
        const val EXTRA_CHARACTERISTIC_UUID = "EXTRA_CHARACTERISTIC_UUID"
        const val EXTRA_PAYLOAD = "EXTRA_PAYLOAD"

        /** The Hub service's Events characteristic (doc/DESIGN-hub.md, Leg 1). */
        const val EVENTS_CHARACTERISTIC_UUID = "00060001-78fc-48fe-8e23-433b3a1942d0"

        const val DESIRED_STATE_PREFERENCES_NAME = "desired_watch_state"
    }
}
