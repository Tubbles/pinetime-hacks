# To Do

The repo is a personal PineTime/InfiniTime hacking playground with several planned features. One is worked at a time. Active: idea 1 (clock sync). See `doc/DESIGN.md` for per-feature design docs.

## Active: 1. Clock stopwatch + timer sync (GrapheneOS clock <-> PineTime)

Sync the GrapheneOS clock app (`com.android.deskclock`) stopwatch and timer with the watch: start, stop, and see both from phone and watch. Both must run in the background on the watch (keep counting/firing when not on the Timer/StopWatch screen). Open to forking and extending both the Android app and InfiniTime, with this repo as the master (git submodules).

Design doc: `doc/DESIGN-clock-sync.md`. Research done. Key findings: both run in the background on the watch already; stock Gadgetbridge carries both directions (no GB fork); the clock app must be forked (renamed package, user-installed) for real control + exact times.

Decisions (resolved): fork the clock app (renamed, user-installed) and run it as the daily clock; both stopwatch and timer; device is on Android 17 (DeskClock submodule at `deskclock/`, branch `17`); button must back out while running; reuse the existing watch StopWatch/Timer screens.

Implementation (firmware leg, on the InfiniTime submodule `clock-sync` branch, base 1.16.1):
- DONE (73916a83): remove `StopWatch::OnButtonPushed()` so the physical button backs out to the watch face while the stopwatch keeps running (Timer already did this). Pause stays on the on-screen play/pause button.
- DONE (77531c3e, build-verified): promote `Controllers::Timer` to a `main.cpp` global via a late `Init()` from DisplayApp, so a BLE service can reach it (StopWatch/Alarm already global). No behavior change.
- TODO: add the ClockSync BLE service `00070000-...` (control WRITE `00070001` + state NOTIFY `00070002`), wired through SystemTask/NimbleController/AppControllers; notify-on-mutate in StopWatchController + Timer with echo suppression; payload = full state snapshot with wall-clock reference.
- Build: `podman run --rm -e DISABLE_POSTBUILD=true --userns=keep-id --security-opt label=disable -v <InfiniTime>:/sources docker.io/infinitime/infinitime-build /opt/build.sh pinetime-app` (rootless podman needs `--userns=keep-id`).
- Transport: enable both BLE Intent API toggles on the PineTime in Gadgetbridge, filter to the ClockSync UUIDs + the fork's package, reconnect + re-add to clear the GATT cache.
- Phone: fork the `deskclock/` submodule (rename package, port to Gradle), add a bridge (DataModel listeners -> CHARACTERISTIC_WRITE; CHARACTERISTIC_CHANGED receiver -> DataModel), sync full snapshots with wall-clock references.
- Build/flash firmware via the Docker image; OTA via Gadgetbridge; don't Validate until reconnect + reboot survive.

## Planned: 2. Scheduled brightness + silent mode

On a schedule configured entirely on the watch (no hard-coded times), switch screen brightness and toggle silent mode. Example: at 20:00 go to lowest brightness + silent; at 07:00 go to middle brightness + full noise. Configuration lives on the watch.

## Planned: 3. Wrist-raise shows a locked screen (touch rejected until button)

Waking the screen via raise-wrist should show the screen but reject touch input, like a lock screen, until the physical button is pressed to fully unlock (prevents accidental touches). Likely needs a lock indicator on the watch face; the Casio G7710 bottom-left corner is free to use for it (the BPM indicator there is expendable).

## Parked: next-event watch face corner (Napper)

Design: `doc/DESIGN.md`, research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Parked pending the user reaching out to Napper AB about cooperation / their thoughts. Standing finding: Napper offers no API, web client, widget, or notification carrying the time, so the only avenue was reading the app's on-screen output (ranked approaches in `doc/DESIGN.md` Leg 1). Nothing to do here until the outreach resolves.

## User written inbox
