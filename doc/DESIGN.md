# Design: "time of next event" in the Casio G7710 corner

Status: current feature status is tracked in `README.md` (parked; see `TODO.md` for why). This file is the design record, kept for when the feature is un-parked.

Provenance: this synthesizes three research passes done 2026-07-19, preserved verbatim-ish as `doc/research-infinitime.md`, `doc/research-napper.md`, `doc/research-gadgetbridge.md`. Every load-bearing claim there is tagged verified (with a URL or `file:line`) or inference. Read those for the evidence; this file is the plan.

## Goal

On the Casio G7710 watch face, the bottom-left corner shows a heart-rate BPM indicator. Replace that corner with the "time of next event" whenever heart rate is not being measured. The first (and only, for now) event source is the Napper app (`com.napper`) on the phone: its predicted next sleep or next wake-up time, whichever comes first. Fully automatic, no manual per-event input. The mechanism should generalize later to other sources (next alarm, calendar) but those are out of scope now.

## System shape

Three independent legs. Each can be built and tested on its own; the interfaces between them are small and stable.

1. Extraction (phone): get the next-event time out of Napper automatically.
2. Transport (phone to watch): carry a small timestamp to the watch a few times a day.
3. Display (watch): a custom InfiniTime build that stores the timestamp and renders it in the G7710 corner.

```
Napper app ──(notification / other)──> [extraction] ──> [transport] ──BLE──> InfiniTime ──> G7710 corner
   phone                                  phone            Gadgetbridge        watch
```

## Leg 3 — Display (InfiniTime firmware)

This is the most certain leg; the architecture actively supports it.

### The corner today

`src/displayapp/screens/WatchFaceCasioStyleG7710.{h,cpp}`. The bottom-left corner is two LVGL labels created in the constructor: `heartbeatIcon` (the heart glyph, anchored `LV_ALIGN_IN_BOTTOM_LEFT`) and `heartbeatValue` (the BPM number, to its right). `Refresh()` reads `heartRateController.HeartRate()` and `heartRateController.State()`. "Not in use" is precisely `State() == HeartRateController::States::Stopped`: in that case the current firmware dims the heart icon to `0x1B1B1B` (near-invisible on black) and blanks the BPM label. See `doc/research-infinitime.md` §1 for the exact lines.

### The change

When `State() == Stopped`, instead of dimming, draw a small glyph plus `HH:MM` for the next event. Add one `Utility::DirtyValue<...>` member for the event time and update the labels in the existing `Refresh()` loop. The face already receives everything it needs except the event data source; add a reference to the new service (below) to its constructor and to its `WatchFaceTraits::Create`.

Caveat (verified, research-infinitime §1): if InfiniTime's optional background heart-rate measurement is enabled, `State()` does not return to `Stopped` between samples, so the corner would rarely be free. Background HR is off by default. Trigger design assumes it stays off; if the user runs continuous HR, we need a different "HR not in the foreground" condition. This is an on-device question (see end).

### Modify the existing face vs. add a new face

Recommended: modify the existing G7710 face. It is a single-user personal fork; modifying costs two files plus a few wiring lines and no enum/registration churn. Adding a parallel face means a new `WatchFace` enum value (persisted in `settings.dat`, so append-only), new Traits, a `CMakeLists.txt` entry, and doubled maintenance. See research-infinitime §2.

### Settings toggle vs. hard-code

The `PTSWeather {On, Off}` per-face setting in `Controllers::Settings` is the exact precedent for a "show event in the corner" toggle (research-infinitime §6). But adding a field to `SettingsData` requires bumping `settingsVersion`, which wipes all saved settings once on first boot of the new firmware. For a personal fork, hard-coding the behavior (corner shows the event whenever HR is Stopped) is simpler and avoids the wipe. Recommended: hard-code for v1; add a setting only if it earns its keep.

## Leg 2 — Transport (phone to watch)

### Chosen path: Gadgetbridge BLE Intent API + a custom InfiniTime characteristic

Since Gadgetbridge 0.82.0 there is a documented, upstream BLE Intent API: any local Android app broadcasts `nodomain.freeyourgadget.gadgetbridge.ble_api.commands.CHARACTERISTIC_WRITE` with extras `EXTRA_DEVICE_ADDRESS` (the watch MAC), `EXTRA_CHARACTERISTIC_UUID`, and `EXTRA_PAYLOAD` (hex bytes), and Gadgetbridge performs the write over its existing connection to the watch. Enabled per device under Developer settings ("BLE Intent API"). See research-gadgetbridge §2b for the URL and PR.

Why this wins over the alternatives (full ranking in research-gadgetbridge §6):
- No Gadgetbridge fork. Keep the stock F-Droid build and its updates. A fork means perpetual rebasing, a signing-key/data migration off F-Droid, and losing updates unless upstreamed.
- Rides Gadgetbridge's managed connection and reconnect logic; no second app fighting for the BLE link.
- The only custom code is on the firmware side, which we are modifying anyway.

Caveats to design around (all verified): any app on the phone can use the API once enabled (fine for a personal device); there is no delivery queue, so the sender must re-send on Gadgetbridge's `BLUETOOTH_CONNECTED` broadcast or on a timer; and Android's GATT service-discovery cache hides a newly added characteristic until the cache is cleared — only the Android-settings unpair does that; see `doc/clock-sync-setup.md` section 2 (research-gadgetbridge §1, FederAndInk lessons, later field-corrected). Keep the payload small to stay under the default BLE MTU (the FederAndInk calendar work needed MTU 200 for ~150-byte writes; our payload is a few bytes).

### The custom InfiniTime service

Model it directly on `SimpleWeatherService` (research-infinitime §3), the established "phone writes structured data, a watch face displays it" precedent:
- Service UUID `00060000-78fc-48fe-8e23-433b3a1942d0` (next free ID in InfiniTime's scheme; `0005` is taken by SimpleWeather), one write-only characteristic `00060001-...`.
- Payload: a version byte, an event-type byte (0 = sleep, 1 = wake), and a 64-bit little-endian local-time UNIX timestamp of the next event. Optionally a short label. This mirrors SimpleWeather's versioned layout so it can grow.
- Store in a `std::optional` in RAM (no persistence; lost on reboot, re-sent by the phone). Expire it in the accessor if the timestamp is in the past or older than a bound, exactly as SimpleWeather expires stale weather at 24 h.
- Wiring cost (~200-300 new lines + ~10 touched): new files in `src/components/ble/`, a member + accessor in `NimbleController`, an `Init()` call, `displayApp.Register(...)` in `SystemTask`, a pointer field in `AppControllers`, and the face's constructor/Traits. Enumerated in research-infinitime §4-5.

### Phone-side sender

Options for what fires the intent (decision pending, see end):
- A tiny purpose-built Android app (~50 lines): a `NotificationListenerService` that reads Napper's notification, computes the next-event timestamp, and broadcasts the Gadgetbridge intent. Most robust, no third-party dependency, but it is an app to build and maintain.
- Tasker (paid) or MacroDroid (free tier): a "notification" trigger on Napper plus a broadcast-intent action. No code, but depends on that tool and its notification-parsing quirks.

Both need the notification to actually contain (or let us derive) the time. See Leg 1.

## Leg 1 — Reading the next-event time from Napper

This is the hard leg. Napper offers no sanctioned integration: no public API, no Health Connect / Google Fit, no calendar/ICS export, no web client, no home-screen widget, and (user-confirmed) no notification carrying the time. Its Terms also restrict extracting data from the app and reverse-engineering it (research-napper §3). The one thing the app does do is display the next event on its own screen. So the approach is to read that on-screen value on a device the user controls, and push it to the watch.

What the screen shows (research-napper §4): the home screen has a central countdown "First nap in 3 min" (a React Native `<Text>`, so likely readable by an Android AccessibilityService) plus absolute clock labels around the ring ("10:07", "19:54", likely custom-Canvas drawn so likely not readable). Reading the central countdown and computing `now + X` yields the absolute next-event time; a single read is enough until the next refresh, so a few reads a day suffice.

### Reading approaches, least extra hardware first

The reader can only read Napper while it is rendered (foreground, or a visible secondary/split window). All of these need the on-screen text to be reachable; see the gate at the end. Same-phone options (1-4) also collapse Leg 1 and Leg 2 onto one device: the reader fires the watch write directly, no relay.

1. Same phone, event-driven read (zero extra hardware, zero disruption, no relay). An AccessibilityService (a small custom app, or Tasker/MacroDroid + AutoInput) fires whenever the user themselves opens Napper — which a parent does several times a day — reads "First nap in X" + type, computes the absolute time, and writes to the watch. No scheduled screen takeover. Coverage depends on how often the app is opened; the watch value expires if it goes stale, which is acceptable. This is the lightest option and the recommended starting point.

2. Same phone, scheduled foreground read (zero extra hardware, brief disruption, no relay). Tasker/MacroDroid periodically launches Napper, reads, and returns to the previous screen. Guarantees freshness but momentarily hijacks the screen; gate it to low-disruption moments (screen already on and idle, on charger) to soften that. Good as a top-up to option 1.

3. Same phone, screenshot + on-device OCR (zero extra hardware, no relay). If the text is not in the accessibility tree, MediaProjection screenshots Napper and on-device OCR (e.g. ML Kit) reads the pixels — this can read the canvas-drawn absolute ring labels that accessibility can't. Needs a one-time screen-capture permission grant (kept alive by a foreground service). The fallback when the gate below fails.

4. Same phone, keep Napper permanently readable via a secondary/virtual display or split-screen (zero extra hardware, no relay, experimental). Host Napper on a virtual display or pinned split-screen so its window is always in the accessibility tree without taking over the main screen. Fiddly and version/app-dependent (needs Napper to tolerate multi-window / secondary display); verify before relying on it.

5. Existing always-on computer running Android (no new hardware if a machine is already on, but adds a relay). Waydroid or an emulator on the user's Linux box runs Napper; a script reads it via adb/uiautomator and relays the value to the carried phone. Caveats: Napper must sign in there (Google Play Integrity commonly blocks sign-in on uncertified GMS — verify first), the machine must stay on, and the relay wrinkle below applies.

6. Dedicated second physical phone (most extra hardware: a device + cable + relay). An old phone left plugged in with Napper open, reading and relaying. Most reliable and fully decoupled from the daily phone, but the heaviest setup.

The relay wrinkle (options 5-6 only): the watch stays BLE-paired to the phone the user carries, so a stationary reader must relay the computed time to that phone (a small push channel: ntfy/MQTT/HTTP or a Tasker-to-Tasker link), which then fires the Gadgetbridge write. Same-phone options avoid this entirely.

The gate (all options): confirm the on-screen "First nap in X" is a readable accessibility text node (`adb shell uiautomator dump` while Napper is foreground). Likely yes given React Native; if not, option 3 (OCR) is the fallback.

### Recommendation: decouple Leg 1 from Legs 2 and 3

Legs 2 and 3 (the Gadgetbridge Intent transport and the InfiniTime corner + BLE service) are clean, low-risk, and buildable now. Leg 1 is the uncertain, effortful part. So build the watch feature first against a dummy/manual event source (the BLE characteristic is generic — it also serves a next-alarm or calendar source later), get the corner working and OTA-flashed, then wire up the Napper reader once the display end is proven. This also means Leg 1's fragility never blocks a working, demonstrable watch feature.

## Build, flash, test

- Firmware: build the fork with the official Docker image `infinitime/infinitime-build` (`doc/buildWithDocker.md`), which produces `pinetime-mcuboot-app-dfu-x.y.z.zip` — the artifact Gadgetbridge's File Installer flashes OTA. Base the fork on release tag 1.16.1 (what the submodule is pinned to) or `main`. See research-infinitime §8.
- UI iteration without hardware: InfiniSim (SDL2 desktop build of the InfiniTime UI). Point it at this tree with `-DInfiniTime_DIR`. The `h`/`H` keys start/stop the simulated heart rate, which is exactly how to test the corner's HR-stopped fallback. BLE is simulated, so the new GATT service can't be exercised end-to-end there; stub its data source or test on hardware. Research-infinitime §8.
- Sealed-watch OTA safety (verified, research-infinitime §8): DFU writes to a secondary slot in external flash; the bootloader swaps and runs the new image unvalidated; if it crashes or the watch resets before you tap Settings → Firmware → Validate, MCUBoot rolls back. A hung firmware is caught by the ~7 s watchdog and rolled back. Physical-button escape hatches work on a sealed watch: hold during boot for blue = force rollback even if validated, red = boot the recovery firmware (BLE/OTA only). OTA never touches the bootloader. Bricking risk is low. Rule: do not Validate until BLE reconnects and a reboot survives with the new corner working.

## Upstreaming

Poor prospects; plan for a personal fork. InfiniTime's vision doc favors minimalism ("personalization is achieved through custom watch faces"), and the standing calendar/next-event PRs (#790, #923, #1958) have sat unmerged for 2-4 years (research-infinitime §7). A small fork of two commits (service + face change) tracking upstream releases is the maintainable shape. The BLE Intent API means the phone side needs no Gadgetbridge fork at all.

## Open decisions and on-device checks

Leg 1 direction (read Napper's on-screen next-event text; prefer the least hardware): start with a same-phone reader triggered when the app is opened (option 1), topped up with a scheduled read (option 2) if freshness needs it, with OCR (option 3) as the fallback if the text isn't accessibility-readable. Off-device options (existing always-on machine, or a dedicated phone) are the fallback if same-phone proves too fragile. See "Reading approaches, least extra hardware first" above.

Sequencing: build Legs 2 and 3 (transport + watch corner) first against a dummy source, decoupled from Leg 1? Recommended, so a working watch feature never waits on the reading problem.

On-device checks (only the user can answer):
- Accessibility exposure: with Napper foreground, does "First nap in 3 min" (and the bottom-bar "10:07") appear as a readable text node? `adb shell uiautomator dump`, Layout Inspector, or the Accessibility Scanner app. This decides whether the on-screen reading approach is viable.
- Do you use InfiniTime's continuous/background heart-rate measurement? (Decides whether "State == Stopped" is a good trigger for the corner.)

Firmware decisions with a recommended default:
- Firmware: modify the existing G7710 face (recommended) vs. add a new face.
- Settings toggle: hard-code for v1 (recommended) vs. add a `Settings` option.
