# Design: stopwatch + timer sync (GrapheneOS clock <-> PineTime)

Status: draft, research complete, pending a few decisions (end of file).

Provenance: research done 2026-08-01 across InfiniTime internals, the AOSP/GrapheneOS clock app, and the Gadgetbridge transport. Claims here trace to that research; load-bearing facts are tagged verified (`file:line` or URL) or inference in the underlying agent findings. This file is the plan.

## Goal

Sync the GrapheneOS clock app's (`com.android.deskclock`) stopwatch and timer with the watch: start, stop, reset, and see current state from either the phone or the watch. Both must keep running in the background on the watch (continue counting / firing when the user is on the watch face or another app).

## Headline findings that shape the plan

- Background execution is already handled upstream. InfiniTime's stopwatch state lives in a survives-navigation controller (`StopWatchController`, a `main.cpp` global), and the timer fires via a FreeRTOS software timer independent of any screen. So "both run in the background on the watch" is already true; the feature only needs to read/write their state, not add background machinery.
- Stock Gadgetbridge carries BOTH directions. Its BLE Intent API supports writes (phone->watch) and, via a second toggle, subscribing to a characteristic and rebroadcasting its notifications (watch->phone). And because Gadgetbridge registers every discovered GATT service before its device-specific gate, a brand-new InfiniTime characteristic works with no Gadgetbridge fork.
- The clock app must be forked. The stock app's external surface is inadequate: timers can only be created (public `SET_TIMER` intent), there is no public stopwatch intent at all, and exact elapsed/remaining time is locked inside a custom notification chronometer that is fragile to read. A fork (renamed package, installed as a user app) gives clean two-way control and exact-time reporting.

Net: the only mandatory firmware fork is the InfiniTime one this project already does; the phone side is a fork of the clock app; Gadgetbridge stays stock.

## Architecture (three legs)

```
GrapheneOS clock fork  ──CHARACTERISTIC_WRITE──▶  Gadgetbridge  ──BLE write──▶  InfiniTime ClockSync svc
   (DataModel)         ◀──CHARACTERISTIC_CHANGED── (stock, Intent  ◀──BLE notify── (control + state chars)
                                                    API, both toggles)
```

## Leg 1 — Watch firmware (InfiniTime fork)

### Background execution: already done
- `StopWatchController` (`src/components/stopwatch/StopWatchController.{h,cpp}`) is a `main.cpp` global passed to both SystemTask and the StopWatch screen; elapsed time is computed on demand from the tick count, so it survives navigation and keeps counting. Nothing to move.
- `Controllers::Timer` (`src/components/timer/Timer.{h,cpp}`) is backed by a one-shot FreeRTOS software timer that fires in the timer-daemon task regardless of the current screen (`TimerCallback` -> DisplayApp queue -> wake + load Timer screen -> ring). Also already background.
- `AlarmController` is the third precedent (SystemTask-owned global, FreeRTOS timer, `System::Messages::SetOffAlarm`).

### The one structural refactor
The Timer controller is currently a member of `DisplayApp` (`DisplayApp.h:107`), reachable only from the display side, not from `NimbleController` (where BLE services live). Promote it to a `main.cpp` global (like `StopWatchController` and `AlarmController`) and thread it into both the DisplayApp and SystemTask/NimbleController constructors. StopWatch and Alarm need no such change.

### New ClockSync BLE service
Model the write path on `SimpleWeatherService` and the notify path on the Music "Events" characteristic (`ble_gattc_notify_custom(connHandle, handle, om)`; the service takes `NimbleController&` to reach `connHandle()`).

Vendor service ID allocation (InfiniTime's `SSSS0000-78fc-48fe-8e23-433b3a1942d0` scheme; upstream uses Music `0000`, Nav `0001`, call char `0002`, Motion `0003`, SimpleWeather `0005`):
- `0006` — reserved for the parked Napper next-event service (`doc/DESIGN.md`); not implemented.
- `0007` — ClockSync (this feature).

Characteristics under service `00070000-78fc-48fe-8e23-433b3a1942d0`:
- `00070001` Control, WRITE — phone->watch full-snapshot commands (see payloads below).
- `00070002` State, NOTIFY (with CCCD) — watch->phone full-snapshot, emitted whenever the watch's own stopwatch/timer changes. The NOTIFY property + CCCD is required so Gadgetbridge's `builder.notify()` subscription succeeds.

Emit-on-mutate: put the notify inside the controller mutators (`StopWatchController::Start/Pause/Clear`, `Timer::StartTimer/StopTimer`) so any origin (watch button or phone write) produces a consistent snapshot, and suppress the emit when the mutation was itself caused by applying a remote snapshot (echo suppression, below).

### Wiring cost
One new service class (~MusicService size, a few hundred lines), the Timer-to-global refactor (touches `main.cpp`, DisplayApp ctor, SystemTask ctor, NimbleController ctor), and notify hooks in the two controllers. The existing StopWatch/Timer screens already render controller state, so external changes show up when a screen is open (push a refresh message if needed); no new watch screen for v1.

### Physical-button behavior: back out while running (required)
Requirement: pressing the physical button while the stopwatch or timer is running must back out of the app to the watch face and leave it running in the background, not stop or pause it.

Timer already behaves this way: it does not override the button, so a press does the default back-navigation and the FreeRTOS timer keeps counting (and still fires in the background). No change needed.

StopWatch does NOT behave this way today and must be changed. `StopWatch::OnButtonPushed()` (`src/displayapp/screens/StopWatch.cpp:246-252`) currently, when running, calls `OnPause()` and returns `true` — so the button pauses the stopwatch and consumes the press, keeping you in the app. That is exactly the interference to remove. Fix: drop the override (delete the method and its declaration at `StopWatch.h:31`) so the button falls through to the default back-navigation while `StopWatchController` keeps counting. Pausing remains available via the on-screen play/pause button (`PlayPauseBtnEventHandler`). This capture was a holdover from when stopwatch state lived in the screen; with the survives-navigation controller it is no longer needed.

### ClockSync wire protocol (v1)

The shared contract between the InfiniTime service and the phone-side fork. One fixed 16-byte little-endian frame is used on both characteristics (symmetric: the phone writes a desired state to Control, the watch notifies its actual state on State).

- Service `00070000-78fc-48fe-8e23-433b3a1942d0`.
- Control `00070001` — WRITE. Phone -> watch command frame.
- State `00070002` — NOTIFY + READ. Watch -> phone state frame, emitted on any watch-side change; readable on demand.

Frame layout:
- `[0]` version = 1.
- `[1]` domain: 0 = stopwatch, 1 = timer.
- `[2]` state: stopwatch { 0 cleared, 1 running, 2 paused }; timer { 0 stopped, 1 running, 2 expired }.
- `[3]` reserved, 0.
- `[4..7]` uint32 value_ms.
- `[8..15]` int64 reference_epoch_ms (UTC milliseconds; InfiniTime's RTC is CTS-synced so both sides share this base).

Semantics (each side computes live time locally, so no per-tick traffic):
- Stopwatch running: `reference_epoch_ms` = now - current_elapsed (a "start-equivalent" wall time); consumer renders `elapsed = now - reference`. `value_ms` unused.
- Stopwatch paused: `value_ms` = frozen elapsed ms; `reference` unused.
- Stopwatch cleared: both unused.
- Timer running: `reference_epoch_ms` = expiry wall time; consumer renders `remaining = reference - now`. `value_ms` = original duration (for total display).
- Timer stopped: `value_ms` = last configured duration; `reference` unused.
- Timer expired: state = 2.

v1 limitations (documented, revisit later): InfiniTime's timer has no pause, so a phone-side timer pause maps to stopped with the remaining duration; stopwatch laps are not synced.

## Leg 2 — Transport (stock Gadgetbridge, >=0.82.0; prefer >=0.84.0)

The BLE Intent API carries both directions (verified against `BleIntentApi.java`, `AbstractBTLESingleDeviceSupport.java`):
- phone->watch: broadcast `...ble_api.commands.CHARACTERISTIC_WRITE` with `EXTRA_DEVICE_ADDRESS`, `EXTRA_CHARACTERISTIC_UUID`, `EXTRA_PAYLOAD` (hex).
- watch->phone: Gadgetbridge subscribes (CCCD write) at service discovery and rebroadcasts `...ble_api.events.CHARACTERISTIC_CHANGED` with `EXTRA_DEVICE_ADDRESS`, `EXTRA_CHARACTERISTIC` (note: not `_UUID`), `EXTRA_PAYLOAD` (hex).

Setup on the PineTime device in Gadgetbridge (Developer / BLE Intent API):
- Enable "Allow GATT interaction through BLE Intent API" (write/read).
- Enable "Broadcast GATT notification Intents through BLE Intent API" (subscribe + rebroadcast).
- Set the BLE-characteristics filter to the ClockSync UUID(s), and the BLE-API package filter to the clock fork's package.
- Reconnect the watch afterward (subscription happens at service discovery), and re-add the device once after the new characteristic first appears to clear Android's stale GATT service cache.

Version floor: both legs exist since 0.82.0; per-characteristic filtering since 0.84.0; current stable (0.92.x) has everything. No delivery queue in the Intent API path, so re-send state on `BLUETOOTH_CONNECTED`.

## Leg 3 — Phone (forked clock app)

Source: `com.android.deskclock` is AOSP DeskClock; GrapheneOS ships an integration fork at `GrapheneOS/platform_packages_apps_DeskClock` (per-Android-version branches, e.g. `16-qpr2`). The ":37" the user saw is a platform-injected build number, not a stable app version. Ultimate upstream mirror: `android.googlesource.com/platform/packages/apps/DeskClock`.

Why fork (not stock): stock gives only timer-create (`SET_TIMER`), no stopwatch intent, and no clean exact-time observation. Forking gives direct access to `DataModel` (start/pause/reset/lap stopwatch; add/start/pause/reset timer) and the `StopwatchListener`/`TimerListener` for exact state out.

Packaging: you cannot replace the platform-signed system `com.android.deskclock` (signature mismatch). Rename the package (e.g. `com.tubbles.deskclock`) and install as an ordinary user app. Build: upstream is Soong-only (no Gradle); port the sources into a Gradle project (rebasing an existing Gradle repackaging of DeskClock onto the current sources is the known path).

Bridge, built into the fork:
- Register a `StopwatchListener` + `TimerListener` on `DataModel`; on any local change, broadcast `CHARACTERISTIC_WRITE` to Gadgetbridge with a full-snapshot payload (phone->watch). Re-send on `BLUETOOTH_CONNECTED`.
- Register a receiver for `...events.CHARACTERISTIC_CHANGED` (read `EXTRA_CHARACTERISTIC` + `EXTRA_PAYLOAD`); apply the watch's snapshot to `DataModel` (watch->phone).
- Echo suppression: guard `DataModel` mutations caused by an inbound snapshot so they do not immediately re-broadcast outward (an "applying remote" flag, or compare against the last-applied snapshot and skip if unchanged).

GrapheneOS notes: notification access is NOT needed (the fork owns its `DataModel`, so no notification-scraping). The Gadgetbridge BLE Intent API toggles are a "restricted setting" for sideloaded apps on GrapheneOS/Android 13+ — the user must open Gadgetbridge's app-info and "Allow restricted settings" before the toggles appear.

## Sync semantics

- State transitions + a reference timestamp only, never per-tick. Each side computes elapsed/remaining locally. BLE delivers notifications only at connection events with jitter >= the 1 Hz tick, so per-tick sync would be both wasteful and inexact.
- Common time base: wall clock. InfiniTime's RTC is CTS-synced to the phone, so both sides share a wall-clock reference. Stopwatch snapshot: `{state in reset|running|paused, accumulated_ms, running_since_wallclock}`; each side renders `accumulated + (now - running_since)` while running. Timer snapshot: `{state, target_wallclock}` (or `remaining_at_pause`); each side renders `target - now`.
- Full snapshots, idempotent, last-writer-wins. A dropped or late packet self-corrects on the next snapshot; applying an identical snapshot is a no-op (also the echo-suppression mechanism).

## Build, flash, test

- Firmware: build `pinetime-mcuboot-app-dfu` from the InfiniTime fork via the official Docker image; OTA via Gadgetbridge's File Installer; do not Validate until BLE reconnect + a reboot survive.
- The GATT service can't be exercised in InfiniSim (BLE is simulated there), so the UI can be iterated in InfiniSim but the sync itself is tested on hardware.

## Repo / submodules

- InfiniTime is already a submodule (pinned 1.16.1).
- Clock app added as a submodule at `deskclock/`: `GrapheneOS/platform_packages_apps_DeskClock`, branch `17` (matches the device's Android 17). This is the authoritative source; the Gradle fork we actually build (renamed package) is derived from it.
- Gadgetbridge stays stock (no submodule needed).

## Decisions (resolved)

- Fork the clock app: yes. Run the renamed fork (`deskclock/` submodule, branch `17`) as the daily clock.
- v1 scope: both stopwatch and timer.
- Physical button backs out while running: required. Timer already does this; StopWatch needs the `OnButtonPushed` override removed (see Leg 1).
- Watch UI for v1: reuse the existing StopWatch/Timer apps (they already reflect controller state); no new screen.

## Remaining checks (non-blocking)

- Confirm the installed Gadgetbridge is >=0.84.0 (for per-characteristic filtering; both legs work from 0.82.0). Current stable is well past this.
- At firmware build time, confirm the InfiniTime version to base the fork on (submodule is pinned 1.16.1; rebase to a newer tag if desired before starting).
