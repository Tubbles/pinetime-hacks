# Design: Hub T, phone-side integrations for the watch (first: the 3D printer's finish time in the next-event corner)

Status: designed 2026-10-27, decisions resolved (end of this file), implementation not started. Feature status lives in `README.md`.

Origin: the Casio G7710 face shows the next alarm as a bell plus HH:MM next to the heart-rate slot (feature 11, InfiniTime `14cb1561`). The user wants a second kind of time in that corner, "when my 3D printer will be done", with its own icon, whichever of the two is closer winning the spot, fed by a new phone app that polls the printer and pushes the estimate to the watch through Gadgetbridge T. The app is meant to host more integrations later, including "smart apps" whose UI the watch shows but whose logic runs on the phone (a photo viewer, a camera feed). `doc/DESIGN.md` (parked, Napper) already reserved the watch side of this: vendor service id `0006` and a phone-written next-event characteristic. This design builds that service, generalized to several event sources, and the printer is its first live source.

## System shape

```
Moonraker on fluiddpi  <--HTTP poll--  Hub T (new app)  --CHARACTERISTIC_WRITE-->  Gadgetbridge T  --BLE write-->  InfiniTime Hub service  -->  G7710 corner
   printer host                          phone              (GB BLE Intent API)        phone              00060001          watch            soonest of alarm / events
```

Three legs, each testable alone. The interfaces between them are a 16-byte-ish frame (phone to watch) and an HTTP query (phone to printer).

## Leg 1: watch firmware (InfiniTime fork)

### Hub service, vendor id 0006

`HubService` in `src/components/ble/`, modelled on `SimpleWeatherService` (phone writes structured data, a face displays it) like the ClockSync and KeyTones services before it. UUIDs follow InfiniTime's `SSSS0000-78fc-48fe-8e23-433b3a1942d0` scheme with `SSSS` = `0006` (`0007` ClockSync, `0008` KeyTones are taken, `0006` was reserved by `doc/DESIGN.md`).

- `00060001` Events, WRITE only. No notify, so no CCCD: the bond's CCCD store holds 8 entries and is full (log 2026-09-17), and a write-only characteristic costs it nothing.
- `00060002` and `00060003` reserved for the remote-app channel (see "Later: smart apps"). Not implemented now.

Event frame (little-endian, one write per event, at most 82 bytes, well inside the 256-byte MTU Gadgetbridge T requests at `PineTimeJFSupport.java:673` and the watch prefers in `syscfg.h`):

| Offset | Field |
|---|---|
| 0 | version, 1 |
| 1 | opcode: 1 set, 2 clear this source, 3 clear all |
| 2 | source id, 1..255, chosen by the phone app (1 = printer); clear all ignores it |
| 3 | reserved, 0 |
| 4..7 | uint32 UTC epoch seconds of the event |
| 8 | icon width in pixels, 1..24 |
| 9 | icon height in pixels, 1..24 |
| 10.. | icon bitmap, 1 bit per pixel, MSB first, each row padded to a whole byte (20x20: 60 bytes, 24x24: 72 bytes) |

A clear carries only the first 4 bytes. The watch validates length, version and dimensions and ignores anything else.

### Event slots

The service owns a small fixed table, `std::array<EventSlot, 4>`, keyed by source id: UTC seconds, icon dimensions, a 72-byte bitmap, and a valid flag. About 450 bytes of static RAM in total (320 for the slots, the rest the service's UUID and attribute tables), which per the standing lesson (log 2026-08-10, "static buffers are not free on this platform") comes straight out of the ~39 KiB heap. That is comparable to the notification store growth of 2026-08-10, and accepted. No persistence: the table is RAM only, lost on reboot, and the phone re-sends on every connect (Leg 2). An accessor `Soonest(nowUtc)` returns the valid slot with the smallest future timestamp, if any. Past-dated slots are skipped (they expire by themselves) and overwritten by the next set.

### The corner on the G7710 face

Today `Refresh()` computes `upcomingAlarmMinutes` from `AlarmController` (armed and `SecondsToAlarm() <= 24 h`) and paints `alarmIcon` (the bell glyph) plus `alarmValue` (HH:MM) right of the heart-rate slot. The heart-rate value owns the spot while it is displayed. The change keeps all of that and widens the candidate set:

- Candidates: the alarm as today, and `HubService::Soonest(now)` converted to seconds-to-event. Both must be within 24 h (HH:MM is ambiguous beyond that, the alarm already applies this window). The smaller seconds-to wins.
- Rendering: the alarm keeps its glyph label. An event is drawn by an `lv_img` object at the same position, sourced from an `lv_img_dsc_t` of format `LV_IMG_CF_INDEXED_1BIT` whose 2-entry palette is {transparent, `color_text`} and whose pixel data is the face's own copy of the slot's bitmap. `LV_IMG_CF_INDEXED` is enabled in `lv_conf.h` (line 227). Alpha formats are not, which is why indexed-1-bit is the format. When the winning slot changes, `lv_img_cache_invalidate_src` on the descriptor before `lv_img_set_src`, or LVGL's 6-entry image cache keeps the old pixels.
- Time text: the event's UTC seconds plus `DateTime::UtcOffset()` (quarter hours, `DateTimeController.h:93`) gives local HH:MM, printed into the same `alarmValue` label. The `DirtyValue` grows from minutes-of-day to (source, minutes-of-day) so a switch between two sources at the same minute still repaints the icon.
- No new setting: the settings menu table is full (20 entries, 2026-09-17), and `doc/DESIGN.md` already chose to hard-code the corner's behaviour.

### Wiring

As for KeyTones: a `HubService` member and `Init()` in `NimbleController`, a pointer in `AppControllers`, a constructor argument and `WatchFaceTraits::Create` line for the G7710. About 250 new lines, a dozen touched.

## Leg 2: transport (Gadgetbridge T, unchanged)

Hub T broadcasts `nodomain.freeyourgadget.gadgetbridge.ble_api.commands.CHARACTERISTIC_WRITE` with `EXTRA_DEVICE_ADDRESS` (the watch MAC), `EXTRA_CHARACTERISTIC_UUID` (`00060001-78fc-48fe-8e23-433b3a1942d0`) and `EXTRA_PAYLOAD` (hex), exactly as the ClockSync bridge and Phone T do. The write path checks only the device address and the initialized state (`BleIntentApi.java:86-130`), not the sender package, and the device-level "Allow GATT interaction" switch is seeded on (GB T `fe6a6c3d3`), so no Gadgetbridge T change and no new runbook toggle. The notify-side package and characteristic filters do not apply to writes.

Three properties of the Intent API shape the app:

- No delivery queue. A write while the watch is away is dropped, and writes are refused until the device is initialized. Hub T therefore keeps a "desired watch state" (one frame per source) and re-sends all of it on every Bluetooth connect and at every poll. The event table is idempotent per source id, so a repeated frame is harmless.
- The connect trigger is the system broadcast `BluetoothDevice.ACTION_ACL_CONNECTED`, the same one the ClockSync bridge uses (`ClockSyncBridge.java:124-132`): it is on Android's implicit-broadcast exemption list (Android docs, "Implicit broadcast exceptions"), so a manifest receiver gets it without a running process, with `BLUETOOTH_CONNECT` granted. Gadgetbridge's own `BLUETOOTH_CONNECTED` broadcast fires exactly at initialization but is implicit and gated by a global developer switch (`DeviceCommunicationService.java:317-327`), so a manifest receiver would never see it. Because Gadgetbridge initializes the device a few seconds after the ACL link, the receiver enqueues the re-send as a one-shot work request with a short delay rather than writing at once. If this proves flaky in the field, the deterministic fix is a small Gadgetbridge T change that fans `BLUETOOTH_CONNECTED` out explicitly to the Intent API packages like `c288c7894` did for notifications.
- A brand-new characteristic is invisible to the phone until Android's GATT cache is refreshed. Gadgetbridge T drops the cache before every DFU (runbook section 2), so the firmware that adds the service arrives with a fresh discovery.

## Leg 3: Hub T, the phone app

A new, small Android app, not a fork: `phone/hub-app/` in this repository (like `phone/clocksync/`, not a submodule), Kotlin, built by CI with the Nix Android shell exactly like Phone T (`phone-dialer.yml`: `nix develop .#android`, Gradle 8, JDK 17, platforms 34 to 36), signed with the committed keystore, artifact `hub-t-apk`, applicationId `com.tubbles.hub`. The name follows the T scheme for the deployed apps. Nothing in it is a fork, so the name is free to change.

### Shape

```
integrations/            one object per source, each returns what the watch should show
  Integration            id (the frame's source id), icon, suspend fun refresh(): WatchCommand? 
  printer/MoonrakerClient  the two HTTP calls
  printer/PrinterEta       state + numbers in, ETA out (pure, unit-tested on the JVM)
watch/
  EventFrame             frame encoder (pure, unit-tested)
  WatchLink              sends a frame through the Intent API, keeps the desired state, resendAll()
  BluetoothConnectedReceiver  ACL_CONNECTED -> delayed resendAll()
  PollNowReceiver        explicit broadcast com.tubbles.hub.POLL_NOW -> expedited refresh (the nudge, below)
work/
  RefreshWorker          WorkManager: run every integration, send what changed, resend the rest
ui/
  one screen: settings (printer URL, poll interval), status (last poll, last result, last frame), "Refresh now", "Send test event"
```

`WatchCommand` is `SetEvent(utcSeconds, icon)` or `ClearEvent`, and `null` means "no opinion, leave the watch alone". Adding an integration later means one more object in the list and one more source id. The transport, scheduling and UI do not change.

### The printer integration

Target: Moonraker on `fluiddpi.local:7125` (homelab `moonraker.conf.j2`), which the Nexa plug powers together with the printer (homelab `doc/printer-power.md`), so "printer off" shows up as "host unreachable". The name resolves through Android's own resolver, which answers `*.local` lookups by mDNS on Wi-Fi since the November 2021 DNS Resolver module (AOSP, "DNS Resolver": VPN and mobile-data connections are excluded, so with an always-on tunnel the URL takes the host's IP instead). Two requests per poll:

1. `GET /printer/objects/query?print_stats&virtual_sdcard&display_status`: `print_stats.state` (`standby`, `printing`, `paused`, `complete`, `error`, `cancelled`), `print_stats.print_duration` (seconds printing, pauses excluded), `print_stats.filename`, `display_status.progress` (from M73, which PrusaSlicer emits, falling back to `virtual_sdcard.progress`, the file-position ratio).
2. `GET /server/files/metadata?filename=...`: `estimated_time`, the slicer's estimate in seconds. Fetched once per filename and cached.

Rule, in one pure function:

- `printing`: ETA = now + remaining. Remaining = `estimated_time - print_duration` while progress is under 5 % (the file-position ratio is meaningless during heating and purge), then `print_duration * (1 - progress) / progress`, never negative. The watch shows an absolute time, so a 15-minute-stale estimate still reads right, and each poll corrects the drift.
- `paused`: clear (the estimate cannot be trusted while the clock runs and the job does not).
- any other state: clear.
- unreachable (connection error, non-2xx): no command. A Wi-Fi blip must not blank the corner, and a stale ETA expires on the watch by itself when its time passes.

Authorization: none. Moonraker's `trusted_clients` covers `192.168.0.0/16` and `10.0.0.0/8` (homelab template), which is where a phone on the home Wi-Fi sits, and no Moonraker user account exists (user, 2026-10-27), so `force_logins` never engages (it overrides trusted clients only once a user has been created, Moonraker configuration docs). The app sends plain requests and has no credential field.

Scheduling: a WorkManager periodic request at the poll interval from the settings, default 15 minutes, which is also WorkManager's floor (a shorter entry is raised to it), constrained to a connected network. Changing the setting re-enqueues the request. Each run refreshes every integration and re-sends the full desired state, so the periodic run doubles as the reconnect fallback. The printer icon is a 20x20 1-bit bitmap drawn in source as twenty lines of `#` and `.` characters. One HTTP round trip per quarter hour is negligible for the battery.

Between polls the phone learns about a print starting or ending through the nudge below, so the interval only bounds how stale the estimate can get, not how late the corner reacts.

Settings are exactly the printer URL (default `http://fluiddpi.local:7125`) and the poll interval (user, 2026-10-27). The watch address is baked in as a constant, `DB:8F:A2:1A:A1:DB`, the way Clock T ships its deployment config, so there is nothing to type at install.

### The nudge from Home Assistant

The phone has no signal of its own that a print started, and a 15-minute period would leave the corner blank for up to 15 minutes into a print. The homelab does have the signal: Moonraker publishes `print_stats.state` to the broker within 5 s (`status_objects`, `status_interval: 5` in `moonraker.conf.j2`) and Home Assistant already acts on it for the power-off and print-finished automations (`doc/printer-power.md` there). One more automation sends the Home Assistant companion app the notification command `command_broadcast_intent` with `intent_package_name: com.tubbles.hub` and `intent_action: com.tubbles.hub.POLL_NOW` whenever the state changes to `printing`, `paused`, `complete`, `cancelled` or `error`. The companion app turns that into an explicit broadcast, which reaches Hub T's manifest receiver whether or not the app is running, and the receiver enqueues an expedited one-shot refresh. The corner then updates within seconds of a print starting, and clears within seconds of it ending. The periodic poll stays as the fallback for a broker or Home Assistant outage, and the watch still only ever receives what a poll of Moonraker says: the nudge carries no data.

The receiver accepts any sender, since the companion app is not signed with our keystore. A stray broadcast can only cause an extra poll. The automation lives in the homelab repository, where a push to `main` is applied as root by ansible-pull, so it goes in as a pull request there.

### What the user sees

Start a print: within seconds through the nudge, or within one poll interval without it (or at once with "Refresh now") the corner shows the printer icon and the finish time, unless the alarm is sooner. The estimate shifts as the print progresses. When the print finishes, is cancelled or paused, the next poll clears the slot. If the phone cannot reach the host, the time stays until it passes. Hub T's own screen shows the last poll's state and the last frame it sent, which is the first place to look when the corner disagrees with Fluidd.

## Later: smart apps

The user's second use for Hub T is apps "the watch presents but that really run on the phone": a photo viewer, a camera feed (spyglass on fluiddpi already serves `/webcam/snapshot` and a 15 fps stream). The shape, so the event work does not paint us into a corner:

- Watch: one generic "Remote" app in the launcher that paints whatever the phone streams and reports touch and button events back. Pixels arrive as strips written straight to the LCD controller (`St7789::DrawBuffer`), never into a full frame buffer: a 240x240 RGB565 frame is 115,200 bytes, three times the whole heap. Strip size and refresh rate get measured on the watch, not assumed.
- Service: `00060002` Frame, WRITE WITHOUT RESPONSE, carrying strip headers plus pixels in whatever packing the measurement favours (RGB565, 8-bit palette, 1-bit). `00060003` Input, NOTIFY, carrying taps, swipes and the button; that one needs a CCCD, which is the point of keeping `00060001` write-only today.
- Phone: a `RemoteApp` plug-in in Hub T renders to a 240x240 bitmap and streams changed strips; the Intent API write path carries it as long as a strip fits one write, otherwise the fork gains a long-write command.

Out of scope now. The event service is independent of it, and nothing in this design needs to change for it.

## Build, flash, test

- Firmware: CI builds the DFU zip as usual (`firmware.yml`). This is the first flash after the CCCD store fix, so it goes through the recovery firmware per `TODO.md`, and the new characteristic appears on the first discovery after it.
- Hub T: `hub.yml` builds `hub-t-apk`. Install, check the printer URL, tap "Send test event": the corner shows the icon and the time, and clears again from the app. Then start a print and watch the time appear and track Fluidd's estimate; let it finish and watch the corner clear.
- Pure parts tested on the JVM in CI like the ClockSync codec: the ETA rule (progress thresholds, each state, missing metadata) and the frame encoder (lengths, padding, clear frames).

## Decisions (resolved, user, 2026-10-27)

1. Icon transport: the bitmap travels in the frame, so a new integration never needs a firmware build. Rejected: an icon id selecting a glyph compiled into the symbol font, lighter on the watch but a `fonts.json` edit and a DFU per new icon.
2. Poll cadence: WorkManager at a user-set interval, default and floor 15 minutes, nothing running in between. Rejected: a foreground service polling every few minutes while a print runs (fresher, but a permanent notification and more code).
3. Unreachable printer: leave the watch alone, the time expires by itself. Rejected: clearing the corner, which blanks it on every Wi-Fi blip.
4. Reaching the printer: `fluiddpi.local` on the home Wi-Fi only; away from home the polls fail and the corner keeps whatever it last got. Moonraker is fully open to the LAN (no user account), so the app has no credential field at all. Android resolves `fluiddpi.local` by mDNS on Wi-Fi (see Leg 3); only an always-on VPN would call for the IP in the URL.
5. Name and package: "Hub T", `com.tubbles.hub`, directory `phone/hub-app/`.
6. First reaction to a print starting or ending: the Home Assistant nudge above (user, 2026-10-27). Rejected: a worker that re-enqueues itself every few minutes (twelve wake-ups an hour for nothing, and Doze defers it anyway).
