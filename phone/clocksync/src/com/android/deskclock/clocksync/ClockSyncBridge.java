/*
 * ClockSync bridge: mirrors the DeskClock stopwatch/timer to a PineTime watch
 * running the InfiniTime ClockSync BLE service, using stock Gadgetbridge's BLE
 * Intent API as the transport.
 *
 * Outbound (phone -> watch): a StopwatchListener + TimerListener on DataModel
 * turn every local change into a 16-byte frame, sent as a CHARACTERISTIC_WRITE
 * broadcast to Gadgetbridge (target package explicit).
 *
 * Inbound (watch -> phone): ClockSyncReceiver hands us frames parsed from
 * Gadgetbridge's CHARACTERISTIC_CHANGED broadcast; we apply them to DataModel.
 *
 * Echo suppression: while an inbound frame is being applied, mApplyingRemote is
 * set so our own listeners do not re-broadcast the change back out. Transitions
 * are also idempotent (we only mutate DataModel when the state actually
 * differs), and identical outbound frames are de-duplicated.
 *
 * All DataModel access here happens on the main thread: init() runs from
 * DeskClockApplication.onCreate(), listener callbacks fire from DataModel
 * mutators (main-looper enforced), and the receiver delivers on the main thread.
 */
package com.android.deskclock.clocksync;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.deskclock.data.DataModel;
import com.android.deskclock.data.Lap;
import com.android.deskclock.data.Stopwatch;
import com.android.deskclock.data.StopwatchListener;
import com.android.deskclock.data.Timer;
import com.android.deskclock.data.TimerListener;

import java.util.List;

public final class ClockSyncBridge {

    private static final String TAG = "ClockSyncBridge";

    // Gadgetbridge BLE Intent API contract. Verified against
    // app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btle/BleIntentApi.java
    // and https://gadgetbridge.org/internals/automations/intents/ .
    private static final String ACTION_CHARACTERISTIC_WRITE =
            "nodomain.freeyourgadget.gadgetbridge.ble_api.commands.CHARACTERISTIC_WRITE";
    private static final String EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS";
    private static final String EXTRA_CHARACTERISTIC_UUID = "EXTRA_CHARACTERISTIC_UUID";
    private static final String EXTRA_PAYLOAD = "EXTRA_PAYLOAD";

    // ClockSync GATT characteristics (InfiniTime ClockSyncService.h).
    static final String CONTROL_CHARACTERISTIC_UUID = "00070001-78fc-48fe-8e23-433b3a1942d0";
    static final String STATE_CHARACTERISTIC_UUID = "00070002-78fc-48fe-8e23-433b3a1942d0";

    // SharedPreferences keys (DeskClock default prefs). See README for how to set.
    static final String PREF_WATCH_MAC = "clocksync_watch_mac";
    static final String PREF_GADGETBRIDGE_PACKAGE = "clocksync_gadgetbridge_package";

    private static final String DEFAULT_GADGETBRIDGE_PACKAGE =
            "nodomain.freeyourgadget.gadgetbridge";

    private static final long UINT32_MAX = 0xFFFFFFFFL;

    private static final ClockSyncBridge INSTANCE = new ClockSyncBridge();

    private Context mAppContext;
    private SharedPreferences mPrefs;
    private boolean mApplyingRemote;
    private ClockSyncFrame mLastSentStopwatchFrame;
    private ClockSyncFrame mLastSentTimerFrame;

    private final StopwatchListener mStopwatchListener = new BridgeStopwatchListener();
    private final TimerListener mTimerListener = new BridgeTimerListener();
    private final BroadcastReceiver mConnectionReceiver = new ConnectionReceiver();

    private ClockSyncBridge() {}

    public static ClockSyncBridge getInstance() {
        return INSTANCE;
    }

    /**
     * Registers the DataModel listeners and pushes the current state once.
     * Call from DeskClockApplication.onCreate() AFTER DataModel.init(), on the
     * main thread.
     *
     * Reads config from DeskClock's default SharedPreferences. On N+ DeskClock
     * moves those into the device-protected storage context, so prefer
     * {@link #init(Context, SharedPreferences)} with the same {@code prefs}
     * object the Application already built if the watch MAC is not found here.
     */
    public void init(Context context) {
        init(context, PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext()));
    }

    /**
     * @param prefs the SharedPreferences holding the ClockSync config keys; pass
     *      the same instance DeskClockApplication uses so the store matches
     */
    public void init(Context context, SharedPreferences prefs) {
        if (mAppContext != null) {
            return;
        }
        mAppContext = context.getApplicationContext();
        mPrefs = prefs;

        DataModel.getDataModel().addStopwatchListener(mStopwatchListener);
        DataModel.getDataModel().addTimerListener(mTimerListener);

        // Re-push state whenever a Bluetooth link comes up. The Intent API has no
        // delivery queue, so a snapshot sent while disconnected is lost; this is
        // the reconnect resync. ACTION_ACL_CONNECTED is an Android system
        // broadcast (fires for any device); we do not read the device out of the
        // intent, so we never touch device details. On Android 12+ the system
        // still only delivers this broadcast to apps holding BLUETOOTH_CONNECT; if
        // that permission is absent the resync simply does not fire and state
        // re-syncs on the next local change instead (see README).
        mAppContext.registerReceiver(mConnectionReceiver,
                new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));

        resendCurrentState();
    }

    /**
     * Re-sends the current stopwatch and timer state to the watch. Safe to call
     * repeatedly; identical frames are de-duplicated.
     */
    public void resendCurrentState() {
        sendStopwatchFrame(buildStopwatchFrame(DataModel.getDataModel().getStopwatch()));
        sendTimerFrame(buildTimerFrame(selectPrimaryTimer()));
    }

    // --- Outbound: DataModel -> frame ---------------------------------------

    private final class BridgeStopwatchListener implements StopwatchListener {
        @Override
        public void stopwatchUpdated(Stopwatch before, Stopwatch after) {
            if (mApplyingRemote) {
                return;
            }
            sendStopwatchFrame(buildStopwatchFrame(after));
        }

        @Override
        public void lapAdded(Lap lap) {
            // v1: laps are not synced (see doc/DESIGN-clock-sync.md).
        }
    }

    private final class BridgeTimerListener implements TimerListener {
        @Override
        public void timerAdded(Timer timer) {
            onTimerChanged();
        }

        @Override
        public void timerUpdated(Timer before, Timer after) {
            onTimerChanged();
        }

        @Override
        public void timerRemoved(Timer timer) {
            onTimerChanged();
        }
    }

    private void onTimerChanged() {
        if (mApplyingRemote) {
            return;
        }
        sendTimerFrame(buildTimerFrame(selectPrimaryTimer()));
    }

    /**
     * Builds the stopwatch frame the way the firmware does (ClockSyncService.cpp
     * BuildStopWatchFrame): running seeds elapsed on the watch via reference =
     * now - elapsed; paused carries frozen elapsed in value_ms.
     */
    ClockSyncFrame buildStopwatchFrame(Stopwatch stopwatch) {
        if (stopwatch.isRunning()) {
            final long elapsed = stopwatch.getTotalTime();
            final long reference = System.currentTimeMillis() - elapsed;
            return new ClockSyncFrame(ClockSyncFrame.DOMAIN_STOPWATCH,
                    ClockSyncFrame.STOPWATCH_RUNNING, 0L, reference);
        }
        if (stopwatch.isPaused()) {
            return new ClockSyncFrame(ClockSyncFrame.DOMAIN_STOPWATCH,
                    ClockSyncFrame.STOPWATCH_PAUSED, clampUint32(stopwatch.getTotalTime()), 0L);
        }
        return new ClockSyncFrame(ClockSyncFrame.DOMAIN_STOPWATCH,
                ClockSyncFrame.STOPWATCH_CLEARED, 0L, 0L);
    }

    /**
     * Builds the timer frame. A running timer carries expiry wall-clock time in
     * reference (the watch renders remaining = reference - now) and the total
     * length in value_ms for display. A paused/reset timer maps to stopped with
     * the remaining duration, because the InfiniTime timer has no pause (v1).
     */
    ClockSyncFrame buildTimerFrame(Timer timer) {
        if (timer == null) {
            return new ClockSyncFrame(ClockSyncFrame.DOMAIN_TIMER,
                    ClockSyncFrame.TIMER_STOPPED, 0L, 0L);
        }
        if (timer.isRunning()) {
            return new ClockSyncFrame(ClockSyncFrame.DOMAIN_TIMER, ClockSyncFrame.TIMER_RUNNING,
                    clampUint32(timer.getTotalLength()), timer.getWallClockExpirationTime());
        }
        if (timer.isExpired() || timer.isMissed()) {
            return new ClockSyncFrame(ClockSyncFrame.DOMAIN_TIMER, ClockSyncFrame.TIMER_EXPIRED,
                    clampUint32(timer.getTotalLength()), timer.getWallClockExpirationTime());
        }
        return new ClockSyncFrame(ClockSyncFrame.DOMAIN_TIMER, ClockSyncFrame.TIMER_STOPPED,
                clampUint32(Math.max(0L, timer.getRemainingTime())), 0L);
    }

    /**
     * v1 mirrors a single timer. Picks the most relevant one: running, then
     * expired/missed, then paused, then reset.
     */
    private Timer selectPrimaryTimer() {
        final List<Timer> timers = DataModel.getDataModel().getTimers();
        Timer best = null;
        for (Timer timer : timers) {
            if (best == null || timerPriority(timer) < timerPriority(best)) {
                best = timer;
            }
        }
        return best;
    }

    private static int timerPriority(Timer timer) {
        if (timer.isRunning()) {
            return 0;
        }
        if (timer.isExpired() || timer.isMissed()) {
            return 1;
        }
        if (timer.isPaused()) {
            return 2;
        }
        return 3; // reset
    }

    private void sendStopwatchFrame(ClockSyncFrame frame) {
        if (frame.equals(mLastSentStopwatchFrame)) {
            return;
        }
        if (sendFrame(frame)) {
            mLastSentStopwatchFrame = frame;
        }
    }

    private void sendTimerFrame(ClockSyncFrame frame) {
        if (frame.equals(mLastSentTimerFrame)) {
            return;
        }
        if (sendFrame(frame)) {
            mLastSentTimerFrame = frame;
        }
    }

    /**
     * @return true if the broadcast was dispatched; false if no watch MAC is
     *      configured (nothing to send to)
     */
    private boolean sendFrame(ClockSyncFrame frame) {
        final String watchMac = getWatchMacAddress();
        if (TextUtils.isEmpty(watchMac)) {
            Log.w(TAG, "no watch MAC configured (" + PREF_WATCH_MAC + "); dropping frame");
            return false;
        }
        final Intent intent = new Intent(ACTION_CHARACTERISTIC_WRITE);
        intent.setPackage(getGadgetbridgePackage());
        intent.putExtra(EXTRA_DEVICE_ADDRESS, watchMac);
        intent.putExtra(EXTRA_CHARACTERISTIC_UUID, CONTROL_CHARACTERISTIC_UUID);
        intent.putExtra(EXTRA_PAYLOAD, ClockSyncFrame.bytesToHex(frame.encode()));
        mAppContext.sendBroadcast(intent);
        return true;
    }

    // --- Inbound: frame -> DataModel ----------------------------------------

    /**
     * Applies a frame received from the watch. Called by ClockSyncReceiver on the
     * main thread. Guards against echoing the change back to the watch.
     */
    public void onRemoteFrame(ClockSyncFrame frame) {
        if (frame.version != ClockSyncFrame.VERSION) {
            Log.w(TAG, "ignoring frame with unknown version " + frame.version);
            return;
        }
        mApplyingRemote = true;
        try {
            if (frame.domain == ClockSyncFrame.DOMAIN_STOPWATCH) {
                applyStopwatch(frame);
            } else if (frame.domain == ClockSyncFrame.DOMAIN_TIMER) {
                applyTimer(frame);
            }
        } finally {
            mApplyingRemote = false;
        }
    }

    private void applyStopwatch(ClockSyncFrame frame) {
        final DataModel dataModel = DataModel.getDataModel();
        final Stopwatch stopwatch = dataModel.getStopwatch();
        switch (frame.state) {
            case ClockSyncFrame.STOPWATCH_RUNNING:
                // The phone cannot reseed its accumulated time via the public API,
                // so an inbound run just starts a fresh stopwatch (v1 limitation).
                if (!stopwatch.isRunning()) {
                    dataModel.startStopwatch();
                }
                break;
            case ClockSyncFrame.STOPWATCH_PAUSED:
                if (stopwatch.isRunning()) {
                    dataModel.pauseStopwatch();
                }
                break;
            case ClockSyncFrame.STOPWATCH_CLEARED:
            default:
                if (!stopwatch.isReset()) {
                    dataModel.resetStopwatch();
                }
                break;
        }
    }

    private void applyTimer(ClockSyncFrame frame) {
        final DataModel dataModel = DataModel.getDataModel();
        final Timer primary = selectPrimaryTimer();
        switch (frame.state) {
            case ClockSyncFrame.TIMER_RUNNING:
                applyTimerRunning(dataModel, primary, frame);
                break;
            case ClockSyncFrame.TIMER_STOPPED:
                if (primary != null && primary.isRunning()) {
                    dataModel.pauseTimer(primary);
                }
                break;
            case ClockSyncFrame.TIMER_EXPIRED:
            default:
                // No-op: a running phone timer expires on its own via TimerService
                // when it reaches zero, so the watch's expiry needs no action.
                break;
        }
    }

    private void applyTimerRunning(DataModel dataModel, Timer primary, ClockSyncFrame frame) {
        if (primary != null && primary.isRunning()) {
            return; // already running; idempotent
        }
        final long remaining = frame.referenceEpochMs - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        // A watch-initiated run creates a fresh phone timer of the remaining
        // length (deleteAfterUse so it cleans up on reset) rather than adopting an
        // existing paused timer. See README for this v1 limitation.
        final Timer created = dataModel.addTimer(remaining, "", true /* deleteAfterUse */);
        dataModel.startTimer(created);
    }

    // --- Config -------------------------------------------------------------

    private String getWatchMacAddress() {
        return mPrefs.getString(PREF_WATCH_MAC, null);
    }

    private String getGadgetbridgePackage() {
        return mPrefs.getString(PREF_GADGETBRIDGE_PACKAGE, DEFAULT_GADGETBRIDGE_PACKAGE);
    }

    private static long clampUint32(long value) {
        if (value < 0L) {
            return 0L;
        }
        return Math.min(value, UINT32_MAX);
    }

    /** Resends state on any Bluetooth ACL connection (reconnect resync). */
    private final class ConnectionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            resendCurrentState();
        }
    }
}
