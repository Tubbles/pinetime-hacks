# Clock sync + key tones: setup and flashing runbook

End-to-end steps to get the stopwatch/timer sync and the in-call key tones working. Design and rationale are in `doc/DESIGN-clock-sync.md` and `doc/DESIGN-intercom-keytones.md`; this file is the practical checklist.

Heads-up before flashing: the key-tones firmware bumps the settings version, so on first boot ALL watch settings reset to defaults (watch face, wake modes, brightness, etc.) — re-set them once.

## 1. Watch firmware (OTA)

The firmware changes live on the InfiniTime submodule branch `clock-sync` (base 1.16.1). Build the OTA DFU package with the official image (rootless podman needs `--userns=keep-id`):

```
podman run --rm --userns=keep-id --security-opt label=disable \
  -v <repo>/InfiniTime:/sources docker.io/infinitime/infinitime-build \
  /opt/build.sh pinetime-mcuboot-app-dfu
```

The `pinetime-mcuboot-app-dfu-1.16.1.zip` lands in `InfiniTime/build/output/`. Flash it over the air with Gadgetbridge's file installer (Device -> ... -> Install / the DFU flow). After it reboots, do NOT tap Settings -> Firmware -> Validate until Bluetooth reconnects and a reboot survives with the watch working; if anything is wrong, let the battery drain or use the button rollback (hold on boot until the pine cone is blue) so MCUBoot reverts. The watch keeps its existing resources; no resource reflash is needed.

## 2. Gadgetbridge T (the Gadgetbridge fork)

The watch is now managed by Gadgetbridge T (CI artifact `gadgetbridge-t-apk`, applicationId `nodomain.freeyourgadget.gadgetbridge.t`), which adds call-state forwarding so the watch's InCall screen opens and closes itself. Stock F-Droid Gadgetbridge also works for everything except that auto-open/close, but only ONE app may own the PineTime:

- Install Gadgetbridge T, grant it notification access, and pair/add the PineTime in it.
- In stock Gadgetbridge (if installed), REMOVE the PineTime device (or at least disable its auto-reconnect): two Gadgetbridges fighting over one watch causes connect/disconnect thrashing. Stock GB may stay for other gadgets.
- Clock T ships with its deployment config baked in (watch MAC, Gadgetbridge T package, Phone T debug package) — no preference-setting needed. The `clocksync_*` preferences still override if anything changes.

Then, on the PineTime device in Gadgetbridge T, open Device settings -> Developer settings / BLE Intent API and:

- Enable "Allow GATT interaction through BLE Intent API" (the phone->watch write path).
- Enable "Broadcast GATT notification Intents through BLE Intent API" (the watch->phone notify path).
- Set the BLE-characteristics filter (comma-separated) to the ClockSync UUIDs and the KeyTones UUID: `00070001-78fc-48fe-8e23-433b3a1942d0` (control), `00070002-78fc-48fe-8e23-433b3a1942d0` (state), `00080001-78fc-48fe-8e23-433b3a1942d0` (key tones).
- Set the BLE-API package filter to the clock fork's applicationId (e.g. `com.tubbles.deskclock`).
- Reconnect the watch afterward (the notify subscription happens at service discovery). If the ClockSync characteristics don't show up, remove the device from Gadgetbridge and re-add it once to clear Android's stale GATT service cache.

GrapheneOS note: the BLE Intent API toggles may be greyed out as a "restricted setting" for a sideloaded Gadgetbridge. Open Gadgetbridge's app-info page, tap the menu, and choose "Allow restricted settings" first.

## 3. Clock app (phone)

Build and install the DeskClock fork with the ClockSync bridge; see `phone/clocksync/README.md` for the fork/build steps and where the watch MAC address is configured. It must be installed under the applicationId you set as the Gadgetbridge package filter above.

## 4. Verify clock sync

Start/stop/reset the stopwatch on the phone; the watch should follow, and vice versa. Same for the timer. If one direction works and the other doesn't, check the matching Gadgetbridge toggle (write vs notify) and the characteristic/package filters.

## 5. Key tones (intercom door)

The digit path is watch -> Gadgetbridge -> clock fork (hub) -> dialer fork -> DTMF on the active call. The clock fork forwards because Gadgetbridge's Intent API only targets one package.

- Install the dialer fork (Fossify Phone based, `com.tubbles.phone`; CI artifact `keytones-dialer-apk` or build `phone/dialer-app` locally) and set it as the default phone app: Settings -> Apps -> Default apps -> Phone app. Only the default dialer's InCallService can inject real DTMF.
- The clock fork forwards to `com.tubbles.phone` by default; the `clocksync_dialer_package` preference overrides it (e.g. for the `.debug` build set `com.tubbles.phone.debug`).
- On the watch, Settings -> Intercom picks the key shown directly on the InCall screen (e.g. 5 for the door).
- Verify: call the phone and answer (on either device); with Gadgetbridge T the watch wakes into the InCall screen by itself, and it dismisses itself when the call ends (the red button must hang up, which also closes the screen). With a call to the intercom active, press the intercom key; the door must open. Without a call, key presses are harmless no-ops. Outgoing calls open the screen too; ringing alone does not (the incoming-call alert handles that).
- Debug tip: watch a `logcat` filtered on `KeyToneReceiver`/`CallManager`, or call a second phone and listen for the tones.
