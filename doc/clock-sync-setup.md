# Clock sync + key tones: setup and flashing runbook

End-to-end steps to get the stopwatch/timer sync and the in-call key tones working. Design and rationale are in `doc/DESIGN-clock-sync.md` and `doc/DESIGN-intercom-keytones.md`; this file is the practical checklist.

Heads-up before flashing: the key-tones firmware bumps the settings version, so on first boot ALL watch settings reset to defaults (watch face, wake modes, brightness, etc.) — re-set them once.

APK updates from CI: all three app artifacts (Gadgetbridge T, Clock T, Phone T) are debug builds signed with the repo's committed keystore (`.github/debug.keystore`), so a newer CI APK installs straight over the old one. Exception: an app installed from an artifact built BEFORE that keystore existed carries a throwaway signature, and Android refuses the update with a bare "app not installed" — uninstall it once and install fresh (Gadgetbridge T loses its device pairing and settings, Clock T its alarms/timers, Phone T its default-dialer role; all must be redone once).

## 1. Watch firmware (OTA)

The firmware changes live on the InfiniTime submodule branch `clock-sync` (base 1.16.1). The normal source is CI: download the `pinetime-mcuboot-app-dfu` artifact from the latest green Firmware run. For local builds, the command is owned by `CLAUDE.md` "Building and CI". Flash the DFU zip over the air with Gadgetbridge's file installer (Device -> ... -> Install / the DFU flow). After it reboots, do NOT tap Settings -> Firmware -> Validate until Bluetooth reconnects and a reboot survives with the watch working; if anything is wrong, let the battery drain or use the button rollback (hold on boot until the pine cone is blue) so MCUBoot reverts. The watch keeps its existing resources; no resource reflash is needed.

## 2. Gadgetbridge T (the Gadgetbridge fork)

The watch is now managed by Gadgetbridge T (CI artifact `gadgetbridge-t-apk`, applicationId `nodomain.freeyourgadget.gadgetbridge.t`), which adds call-state forwarding so the watch's InCall screen opens and closes itself. Stock F-Droid Gadgetbridge also works for everything except that auto-open/close, but only ONE app may own the PineTime:

- Install Gadgetbridge T, grant it notification access, and pair/add the PineTime in it.
- In stock Gadgetbridge (if installed), REMOVE the PineTime device (or at least disable its auto-reconnect): two Gadgetbridges fighting over one watch causes connect/disconnect thrashing. Stock GB may stay for other gadgets.
- Clock T ships with its deployment config baked in (watch MAC, Gadgetbridge T package, Phone T debug package) — no preference-setting needed. The `clocksync_*` preferences still override if anything changes.

Then, on the PineTime device in Gadgetbridge T, open Device settings -> Developer settings / BLE Intent API and:

- Enable "Allow GATT interaction through BLE Intent API" (the phone->watch write path).
- Enable "Broadcast GATT notification Intents through BLE Intent API" (the watch->phone notify path).
- Set the BLE-characteristics filter (comma-separated) to the two NOTIFY characteristics: `00070002-78fc-48fe-8e23-433b3a1942d0` (ClockSync state), `00080001-78fc-48fe-8e23-433b3a1942d0` (key tones). The filter governs notify subscriptions only; do NOT list write-only characteristics like the ClockSync control `00070001` (writes need no filter, and a subscription attempt on them is a pointless extra GATT action).
- Set the BLE-API package filter to the clock fork's applicationId (e.g. `com.tubbles.deskclock.debug` for the CI debug artifact).
- Reconnect the watch afterward (the notify subscription happens at service discovery). If the ClockSync characteristics don't show up, or a DFU fails with "GATT REQ NOT SUPPORTED" (error 6): Android's GATT service cache is stale. The fingerprint in a log is service "discovery" completing in tens of milliseconds instead of seconds. Field-verified levers, in order of reliability: (1) Gadgetbridge T (from the `41ffedc` run onward) drops the cache automatically before every DFU, so flashing needs no manual step; (2) nRF Connect -> connect -> "Refresh services" forces the same invalidation manually for non-DFU cases; (3) firmware with the Service Changed announcement (see LOG.md 2026-08-03) tells the phone to rediscover by itself after every firmware update. NOT reliable, all field-disproven: toggling Bluetooth, removing the device inside Gadgetbridge, reinstalling Gadgetbridge, and even unpairing in Android's Bluetooth settings (2026-08-03: a DFU failed with a stale cache on a completely fresh bond).

Troubleshooting — Gadgetbridge stuck at "Connecting" while the watch shows the link as up: some GATT action in the init transaction failed, which aborts the rest of init, and the device never reaches INITIALIZED. The telltale signature of that half-state is that key tones and watch->phone sync still work (notification forwarding is not gated on the initialized state, and the watch remembers subscriptions in the bond) while EVERYTHING phone->watch is dead (Intent-API writes and native commands are refused for a non-initialized device) — so hang-up, in-call auto-open, and phone->watch sync all fail together. Gadgetbridge T builds with the init-order fix (see the LOG.md 2026-08-02 entry on the stuck-connecting state) initialize the device before the Intent-API subscriptions, so a failed subscription no longer bricks the connection. To find WHICH action failed, enable "Write log files" in Gadgetbridge T's settings, reconnect once, and look for "failed btle action, aborting transaction" in the log. Re-pairing (unpair in Android's Bluetooth settings, re-add in GB) recovers, at the cost of a fresh GATT discovery.

GrapheneOS note: the BLE Intent API toggles may be greyed out as a "restricted setting" for a sideloaded Gadgetbridge. Open Gadgetbridge's app-info page, tap the menu, and choose "Allow restricted settings" first.

## 3. Clock app (phone)

Install Clock T (CI artifact `clocksync-clock-apk`, applicationId `com.tubbles.deskclock.debug`). Its deployment config (watch MAC, Gadgetbridge T package, Phone T debug package) is baked in, so there is nothing to configure — but the applicationId must match the Gadgetbridge package filter set above. Bridge internals and the preference override keys are documented in `phone/clocksync/README.md`.

## 4. Verify clock sync

Start/stop/reset the stopwatch on the phone; the watch should follow, and vice versa. Same for the timer. If one direction works and the other doesn't, check the matching Gadgetbridge toggle (write vs notify) and the characteristic/package filters.

## 5. Key tones (intercom door)

The digit path is watch -> Gadgetbridge -> clock fork (hub) -> dialer fork -> DTMF on the active call. The clock fork forwards because Gadgetbridge's Intent API only targets one package.

- Install the dialer fork (Fossify Phone based, `com.tubbles.phone`; CI artifact `keytones-dialer-apk` or build `phone/dialer-app` locally) and set it as the default phone app: Settings -> Apps -> Default apps -> Phone app. Only the default dialer's InCallService can inject real DTMF.
- The clock fork forwards to `com.tubbles.phone` by default; the `clocksync_dialer_package` preference overrides it (e.g. for the `.debug` build set `com.tubbles.phone.debug`).
- On the watch, Settings -> Intercom picks the key shown directly on the InCall screen (e.g. 5 for the door).
- Verify: call the phone and answer (on either device); with Gadgetbridge T the watch wakes into the InCall screen by itself, and it dismisses itself when the call ends (the red button must hang up, which also closes the screen). With a call to the intercom active, press the intercom key; the door must open. Without a call, key presses are harmless no-ops. Outgoing calls open the screen too; ringing alone does not (the incoming-call alert handles that).
- Debug tip: watch a `logcat` filtered on `KeyToneReceiver`/`CallManager`, or call a second phone and listen for the tones.
