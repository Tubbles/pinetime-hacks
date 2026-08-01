# Clock sync: setup and flashing runbook

End-to-end steps to get the stopwatch/timer sync working. Design and rationale are in `doc/DESIGN-clock-sync.md`; this file is the practical checklist. The firmware side is implemented and build-verified; the phone app side (`phone/clocksync/`) is built on your Android machine.

## 1. Watch firmware (OTA)

The firmware changes live on the InfiniTime submodule branch `clock-sync` (base 1.16.1). Build the OTA DFU package with the official image (rootless podman needs `--userns=keep-id`):

```
podman run --rm --userns=keep-id --security-opt label=disable \
  -v <repo>/InfiniTime:/sources docker.io/infinitime/infinitime-build \
  /opt/build.sh pinetime-mcuboot-app-dfu
```

The `pinetime-mcuboot-app-dfu-1.16.1.zip` lands in `InfiniTime/build/output/`. Flash it over the air with Gadgetbridge's file installer (Device -> ... -> Install / the DFU flow). After it reboots, do NOT tap Settings -> Firmware -> Validate until Bluetooth reconnects and a reboot survives with the watch working; if anything is wrong, let the battery drain or use the button rollback (hold on boot until the pine cone is blue) so MCUBoot reverts. The watch keeps its existing resources; no resource reflash is needed.

## 2. Gadgetbridge (stock, F-Droid, >=0.84.0)

On the PineTime device in Gadgetbridge, open Device settings -> Developer settings / BLE Intent API and:

- Enable "Allow GATT interaction through BLE Intent API" (the phone->watch write path).
- Enable "Broadcast GATT notification Intents through BLE Intent API" (the watch->phone notify path).
- Set the BLE-characteristics filter to the two ClockSync UUIDs: `00070001-78fc-48fe-8e23-433b3a1942d0` (control) and `00070002-78fc-48fe-8e23-433b3a1942d0` (state).
- Set the BLE-API package filter to the clock fork's applicationId (e.g. `com.tubbles.deskclock`).
- Reconnect the watch afterward (the notify subscription happens at service discovery). If the ClockSync characteristics don't show up, remove the device from Gadgetbridge and re-add it once to clear Android's stale GATT service cache.

GrapheneOS note: the BLE Intent API toggles may be greyed out as a "restricted setting" for a sideloaded Gadgetbridge. Open Gadgetbridge's app-info page, tap the menu, and choose "Allow restricted settings" first.

## 3. Clock app (phone)

Build and install the DeskClock fork with the ClockSync bridge; see `phone/clocksync/README.md` for the fork/build steps and where the watch MAC address is configured. It must be installed under the applicationId you set as the Gadgetbridge package filter above.

## 4. Verify

Start/stop/reset the stopwatch on the phone; the watch should follow, and vice versa. Same for the timer. If one direction works and the other doesn't, check the matching Gadgetbridge toggle (write vs notify) and the characteristic/package filters.
