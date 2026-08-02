# ClockSync phone bridge (DeskClock fork side)

The phone half of the stopwatch/timer sync between an Android clock app and a PineTime watch running InfiniTime's custom **ClockSync** BLE service. The transport is Gadgetbridge's BLE Intent API; the deployment runs the Gadgetbridge T fork (`phone/gadgetbridge-app`), but stock Gadgetbridge (>= 0.84.0) also carries the sync — the fork only adds call-state forwarding on top. The watch firmware and wire protocol are defined in this repo at `InfiniTime/src/components/ble/ClockSyncService.{h,cpp}` and `doc/DESIGN-clock-sync.md`.

**Status: this directory is the canonical, plain-JVM copy — the plan below has been executed.** The shipping variant lives in the `phone/deskclock-app` submodule (Clock T), is built by CI (`clocksync-clock-apk`), and runs on the actual phone; see "Relation to the shipping fork" at the bottom for the deliberate divergences. These canonical sources themselves are compiled only as far as the plain-JVM codec test (`test/`); the API tables below were written against the `deskclock/` submodule (pristine AOSP checkout) and Gadgetbridge's real `BleIntentApi.java`, each tagged *verified* with a `file:line` or URL. For live setup steps use `doc/clock-sync-setup.md`, which supersedes the setup sections here where they differ.

## Files

- `src/com/android/deskclock/clocksync/ClockSyncFrame.java` — pure codec for the 16-byte little-endian wire frame plus hex helpers. No Android imports; unit-testable on a plain JVM.
- `src/com/android/deskclock/clocksync/ClockSyncBridge.java` — the core. Registers a `StopwatchListener` + `TimerListener` on `DataModel`, turns each change into a frame, and sends it as a Gadgetbridge `CHARACTERISTIC_WRITE` broadcast. Applies inbound frames back onto `DataModel`. Holds the watch MAC in `SharedPreferences`.
- `src/com/android/deskclock/clocksync/ClockSyncReceiver.java` — manifest `BroadcastReceiver` for Gadgetbridge's `CHARACTERISTIC_CHANGED` event, filtered to the state characteristic.

The Java package is `com.android.deskclock.clocksync` so the bridge can call DeskClock internals. The fork keeps Java package names and only changes `applicationId` (see "Fork and build").

## Architecture

```
DeskClock fork  ──CHARACTERISTIC_WRITE──▶  Gadgetbridge  ──BLE write (00070001)──▶  InfiniTime ClockSync
 (DataModel)    ◀─CHARACTERISTIC_CHANGED── (stock, Intent  ◀──BLE notify (00070002)── (control + state chars)
                                            API, 2 toggles)
```

## (a) DeskClock DataModel API used (verified)

Paths below are relative to the `deskclock/` submodule (`deskclock/src/com/android/deskclock/...`). All were read from the pinned submodule checkout.

Listener registration and stopwatch control (`data/DataModel.java`):

- `public void addStopwatchListener(StopwatchListener stopwatchListener)` — DataModel.java:737 (verified)
- `public void addTimerListener(TimerListener timerListener)` — DataModel.java:406 (verified)
- `public Stopwatch getStopwatch()` — DataModel.java:753 (verified)
- `public Stopwatch startStopwatch()` — DataModel.java:761 (verified)
- `public Stopwatch pauseStopwatch()` — DataModel.java:769 (verified)
- `public Stopwatch resetStopwatch()` — DataModel.java:777 (verified)

Timer list and control (`data/DataModel.java`):

- `public List<Timer> getTimers()` — DataModel.java:422 (verified)
- `public Timer addTimer(long length, String label, boolean deleteAfterUse)` — DataModel.java:459 (verified)
- `public void startTimer(Timer timer)` — DataModel.java:475 (verified)
- `public void pauseTimer(Timer timer)` — DataModel.java:499 (verified)
- `public Timer resetTimer(Timer timer)` — DataModel.java:517 (verified; available, not used by v1 inbound path)

Stopwatch state (`data/Stopwatch.java`):

- `public enum State { RESET, RUNNING, PAUSED }` — Stopwatch.java:30 (verified)
- `public boolean isReset() / isRunning() / isPaused()` — Stopwatch.java:59-61 (verified)
- `public long getTotalTime()` — Stopwatch.java:66 (verified). Returns accumulated ms while paused; while running returns `accumulated + max(0, now() - lastStartTime)`. This is a duration, not a clock reading.

Timer state (`data/Timer.java`):

- `public enum State { RUNNING(1), PAUSED(2), EXPIRED(3), RESET(4), MISSED(5) }` — Timer.java:42 (verified)
- `public boolean isRunning() / isPaused() / isExpired() / isMissed() / isReset()` — Timer.java:127-131 (verified)
- `public long getTotalLength()` — Timer.java:125 (verified; original length plus any user-added minutes)
- `public long getRemainingTime()` — Timer.java:144 (verified; frozen value while paused/reset, live otherwise)
- `public long getWallClockExpirationTime()` — Timer.java:170 (verified). Returns `lastStartWallClockTime + remainingTime`. Legal only in RUNNING/EXPIRED/MISSED (throws otherwise), which is exactly where the bridge calls it.

Listener callback signatures:

- `void stopwatchUpdated(Stopwatch before, Stopwatch after)` and `void lapAdded(Lap lap)` — `data/StopwatchListener.java:28,33` (verified)
- `void timerAdded(Timer timer)`, `void timerUpdated(Timer before, Timer after)`, `void timerRemoved(Timer timer)` — `data/TimerListener.java:27,33,38` (verified)

**Time base (verified, load-bearing).** `Utils.wallClock()` -> `DataModel.currentTimeMillis()` -> `System.currentTimeMillis()` (Utils.java:549; TimeModel.java:40), i.e. UTC epoch ms. `Utils.now()` -> `DataModel.elapsedRealtime()` -> `SystemClock.elapsedRealtime()` (Utils.java:545; TimeModel.java:47). A `Timer`'s `lastStartWallClockTime` is set from `wallClock()` at start, so `getWallClockExpirationTime()` is a UTC epoch-ms instant, which is exactly what the frame's `reference_epoch_ms` needs. The stopwatch's running reference is computed as `System.currentTimeMillis() - getTotalTime()`.

## (b) Gadgetbridge BLE Intent API strings used (verified)

Verified against the source `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btle/BleIntentApi.java` (https://codeberg.org/Freeyourgadget/Gadgetbridge) and https://gadgetbridge.org/internals/automations/intents/ . The literal string value of each extra key equals its constant name (e.g. the key `EXTRA_DEVICE_ADDRESS` has the literal value `"EXTRA_DEVICE_ADDRESS"`).

Outbound, phone -> watch (we send this):

- Action: `nodomain.freeyourgadget.gadgetbridge.ble_api.commands.CHARACTERISTIC_WRITE` (verified)
- Extra `EXTRA_DEVICE_ADDRESS` = watch MAC string (verified)
- Extra `EXTRA_CHARACTERISTIC_UUID` = control char UUID string (verified)
- Extra `EXTRA_PAYLOAD` = the 16-byte frame as a hex string (verified; Gadgetbridge parses it with `StringUtils.hexToBytes`, case-insensitive)

Inbound, watch -> phone (we receive this):

- Action: `nodomain.freeyourgadget.gadgetbridge.ble_api.events.CHARACTERISTIC_CHANGED` (verified)
- Extra `EXTRA_DEVICE_ADDRESS` = watch MAC string (verified)
- Extra `EXTRA_CHARACTERISTIC` = characteristic UUID string (verified). **This is the trap:** the changed *event* uses `EXTRA_CHARACTERISTIC`, whereas the write *command* uses `EXTRA_CHARACTERISTIC_UUID`. They are different keys. `ClockSyncReceiver` reads `EXTRA_CHARACTERISTIC`; `ClockSyncBridge` writes `EXTRA_CHARACTERISTIC_UUID`.
- Extra `EXTRA_PAYLOAD` = the value as a hex string (verified; Gadgetbridge produces it with `StringUtils.bytesToHex`)

Two device preferences in Gadgetbridge shape delivery (verified in `BleIntentApi.java`):

- `PREFS_KEY_DEVICE_BLE_API_PACKAGE` — when set, Gadgetbridge calls `intent.setPackage(intentApiPackage)` on the CHANGED broadcast. **Setting this to the fork's `applicationId` is required**, because a manifest-declared receiver receives an app-custom broadcast on Android 8+ only when it is package-targeted (explicit). Left blank, the broadcast is implicit and the OS will not deliver it to `ClockSyncReceiver`.
- `PREFS_KEY_DEVICE_BLE_API_CHARACTERISTIC` — an optional per-characteristic allowlist checked before broadcasting. Recommended: set it to the state UUID so only ClockSync notifications are rebroadcast.

## Wire protocol (matches firmware)

Fixed 16-byte little-endian frame, identical on both characteristics. This mirrors the firmware exactly; the authority is `InfiniTime/src/components/ble/ClockSyncService.cpp` (`ReadUInt32`/`ReadInt64`/`BuildStopWatchFrame`/`BuildTimerFrame`, verified) and `doc/DESIGN-clock-sync.md`.

- `[0]` version = 1
- `[1]` domain: 0 = stopwatch, 1 = timer
- `[2]` state: stopwatch {0 cleared, 1 running, 2 paused}; timer {0 stopped, 1 running, 2 expired}
- `[3]` reserved = 0
- `[4..7]` uint32 `value_ms` (LE)
- `[8..15]` int64 `reference_epoch_ms` (LE, UTC ms)

Semantics the bridge implements (each side computes live time locally):

- Stopwatch running: `reference = now_utc - elapsed`; watch renders `elapsed = now - reference`. `value_ms` = 0. The watch seeds its elapsed from `reference`, not from `value_ms` (verified ClockSyncService.cpp:104-108, :148), so `reference` is the load-bearing field here.
- Stopwatch paused: `value_ms` = frozen elapsed (`getTotalTime()`); `reference` = 0.
- Stopwatch cleared: zeros.
- Timer running: `reference` = expiry wall-clock ms (`getWallClockExpirationTime()`); watch renders `remaining = reference - now`. `value_ms` = total length (`getTotalLength()`), for display. Note: the firmware ignores `value_ms` when applying a running timer and re-derives everything from `reference` (verified ClockSyncService.cpp:121-128), so `value_ms` here is cosmetic.
- Timer stopped: `value_ms` = remaining/last duration; `reference` = 0.
- Timer expired: state = 2.

## (c) Integration into the fork

### 1. Initialize the bridge

In `DeskClockApplication.onCreate()`, right after the existing `DataModel` init (`deskclock/src/com/android/deskclock/DeskClockApplication.java:40`, verified), add the bridge init and pass the same `prefs`:

```java
DataModel.getDataModel().init(applicationContext, prefs);
UiDataModel.getUiDataModel().init(applicationContext, prefs);
// ... existing Controller setup ...
com.android.deskclock.clocksync.ClockSyncBridge.getInstance().init(applicationContext, prefs);
```

Passing the same `prefs` object guarantees the bridge reads the config from the same store DeskClock uses (on N+ DeskClock moves its default prefs into the device-protected storage context; see DeskClockApplication.java:50-63). A one-arg `init(applicationContext)` overload also exists but resolves `PreferenceManager.getDefaultSharedPreferences` itself, which may point at a different store on N+ — prefer the two-arg form.

### 2. AndroidManifest additions

Inside `<application>` of the fork's manifest:

```xml
<receiver
    android:name="com.android.deskclock.clocksync.ClockSyncReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="nodomain.freeyourgadget.gadgetbridge.ble_api.events.CHARACTERISTIC_CHANGED" />
    </intent-filter>
</receiver>
```

`android:exported="true"` is required to receive a broadcast originating in Gadgetbridge, and must be explicit on Android 12+.

Optional permission, at the top level, for the reconnect resync only:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

The bridge registers a receiver for `BluetoothDevice.ACTION_ACL_CONNECTED` to re-push state after a link comes up (the Intent API has no delivery queue, so a snapshot sent while disconnected is lost). On Android 12+ the system delivers that broadcast only to apps holding `BLUETOOTH_CONNECT`; without the permission the app does not crash, the resync just does not fire, and state re-syncs on the next local change instead. If you would rather not grant it, omit the permission and call `ClockSyncBridge.getInstance().resendCurrentState()` from wherever suits (e.g. when the app comes to the foreground).

### 3. Set the watch MAC (and optionally the Gadgetbridge package)

The bridge reads two keys from the DeskClock default `SharedPreferences`:

- `clocksync_watch_mac` (String) — the PineTime's Bluetooth MAC, uppercase colon form, e.g. `C1:9A:2B:3C:4D:5E`. Required in BOTH directions: with it empty the bridge logs a warning and sends nothing, and `ClockSyncReceiver` drops every inbound frame (frames are only applied when the broadcast's `EXTRA_DEVICE_ADDRESS` matches this MAC, so a second Gadgetbridge-managed device cannot drive the clock).
- `clocksync_gadgetbridge_package` (String, optional) — defaults to `nodomain.freeyourgadget.gadgetbridge`. Set to `nodomain.freeyourgadget.gadgetbridge.nightly` if you run the Gadgetbridge nightly build.
- `clocksync_dialer_package` (String, optional) — defaults to `com.tubbles.phone`. Target for forwarded key-tone frames (see "Hub role" below); set to `com.tubbles.phone.debug` when running the dialer fork's debug build.

Because these live in the device-protected prefs on N+, the simplest robust way to set them is in the fork itself. Clock T does exactly that: the deployment values (watch MAC, Gadgetbridge T package, Phone T debug package) are baked in via `BuildConfig` and seeded into the prefs at first run, so an installed Clock T needs no configuration; the prefs remain the override mechanism. Find the MAC in Gadgetbridge (device info) or Android's Bluetooth settings.

### 4. Gadgetbridge setup (stock app, on the phone)

On the PineTime device entry in Gadgetbridge, open Device Settings -> Developer settings -> BLE Intent API and:

- Enable "Allow GATT interaction through BLE Intent API" (the write path). (verified: `BLE_API_COMMAND_WRITE` handler)
- Enable "Broadcast GATT notification Intents through BLE Intent API" (the subscribe + rebroadcast path). (verified: `CHARACTERISTIC_CHANGED` broadcaster)
- Set the BLE-API package filter (`PREFS_KEY_DEVICE_BLE_API_PACKAGE`) to the fork's `applicationId` (e.g. `com.tubbles.deskclock`). Required, per (b).
- Set the BLE-characteristics filter (`PREFS_KEY_DEVICE_BLE_API_CHARACTERISTIC`) to the state UUID `00070002-78fc-48fe-8e23-433b3a1942d0` (recommended).
- Reconnect the watch afterward (Gadgetbridge subscribes to notifications at service discovery), and if the ClockSync characteristic is newly appearing, remove and re-add the device once to clear Android's stale GATT service cache.

GrapheneOS note (confirmed on device): the BLE Intent API toggles can be a "restricted setting" for sideloaded apps on Android 13+, so you may need to open Gadgetbridge's app-info and "Allow restricted settings" before the toggles appear. Notification access is NOT required for the sync (the fork owns its `DataModel`; nothing is scraped from notifications).

## (d) Fork and build

`com.android.deskclock` is AOSP DeskClock; the `deskclock/` submodule is the GrapheneOS integration fork, which builds only under Soong (`deskclock/Android.bp`, verified) with no Gradle. To ship this bridge:

- You cannot replace the platform-signed system `com.android.deskclock` (signature mismatch). Change the `applicationId` (e.g. `com.tubbles.deskclock`) and install the fork as an ordinary user app. Keep the Java package names (`com.android.deskclock.*`) unchanged so this bridge and the rest of the app still resolve DeskClock internals; only the `applicationId` differs.
- Port the sources into a Gradle project: rebase the current DeskClock sources onto an existing community Gradle repackaging of DeskClock, then open it in Android Studio. (This is the known path; verify the repackaging you pick against the submodule's source version before trusting it.)
- Drop these three files into the fork at the path matching the package, `.../java/com/android/deskclock/clocksync/` (exact prefix depends on the Gradle module layout, typically `app/src/main/java/...`).
- Build the APK in Android Studio and install it.

**All of the above has been executed** in the shipping fork (see "Relation to the shipping fork"): instead of Gradle-porting the Soong-only GrapheneOS tree, the fork rebased onto BlackyHawky/Clock tag 2.20, whose Gradle toolchain matched and whose `DataModel` stays close to AOSP. This directory itself contains only the canonical bridge sources and this guide.

### Watch side and test

- Firmware: build `pinetime-mcuboot-app-dfu` from the InfiniTime fork and OTA it via Gadgetbridge's File Installer. Do not Validate the image until a BLE reconnect and a reboot both survive. (See `doc/DESIGN-clock-sync.md` and `doc/LOG.md`.)
- The GATT path cannot be exercised in InfiniSim (BLE is simulated there); test the sync on hardware. Sanity-check `ClockSyncFrame` with a plain-JVM unit test (encode a known frame, assert the 16 bytes; round-trip decode) before integrating.

## v1 limitations

- Single timer only. `DataModel` supports many timers; the bridge mirrors one "primary" (running > expired/missed > paused > reset). Other timers are not synced.
- The InfiniTime timer has no pause, so a phone-side paused timer is sent as *stopped* carrying the remaining duration. Conversely, an inbound *stopped* frame pauses the phone's primary timer (non-destructive) rather than resetting it; the watch cannot express pause-vs-reset.
- An inbound *running* timer creates a fresh phone timer (`deleteAfterUse`) of the remaining length rather than adopting an existing paused one.
- Stopwatch elapsed cannot be reseeded through DeskClock's public API, so an inbound *running* stopwatch starts the phone's stopwatch from its own current value; it does not jump to the watch's elapsed. The watch, by contrast, does seed its elapsed from the phone's `reference`.
- Inbound *expired* timer is a no-op: a running phone timer expires on its own via `TimerService`.
- Stopwatch laps are not synced.

Echo suppression: while an inbound frame is applied, an `mApplyingRemote` flag stops the bridge's own listeners from re-broadcasting it; transitions are also idempotent (mutate only when the state actually differs), and identical outbound frames are de-duplicated against the last one sent. The dedup cache is dropped on every `ACTION_ACL_CONNECTED` before the resync: frames broadcast while the link was down are lost (no delivery queue), so the byte-identical resync frame must not be suppressed.

Security note (accepted v1 risk): `ClockSyncReceiver` is exported and Gadgetbridge's broadcasts are unsigned, so any app on the phone that knows the watch MAC could forge a `CHARACTERISTIC_CHANGED` broadcast and start/stop the stopwatch or a timer. There is no clean sender check for unsigned broadcasts; the worst case is a nuisance state change.

Hub role (key tones): Gadgetbridge's Intent API targets a single package, and this app is it. `ClockSyncReceiver` therefore also receives the watch's DTMF key-tone frames (characteristic `00080001-...`, from the InfiniTime KeyTonesService) and re-broadcasts them, explicitly targeted, to the dialer fork (`clocksync_dialer_package`), which plays them on the active call. The MAC filter applies before forwarding. See `doc/DESIGN-intercom-keytones.md`.

## Relation to the shipping fork

The copy that actually ships lives in the `phone/deskclock-app` submodule (a BlackyHawky/Clock fork, tag 2.20), whose `DataModel` diverges slightly from the AOSP/GrapheneOS DeskClock this canonical copy targets. To regenerate the fork copy from this one: rename the package `com.android.deskclock` -> `com.best.deskclock`, change `stopwatchUpdated(before, after)` to the single-arg `stopwatchUpdated(after)` and drop `lapAdded`, and add BlackyHawky's 4th `buttonTime` argument to `DataModel.addTimer`. `ClockSyncFrame.java` must stay byte-identical modulo the package line; CI enforces that (`.github/workflows/phone-app.yml`).
