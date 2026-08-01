# To Do

The repo is a personal PineTime/InfiniTime hacking playground with several planned features. One is worked at a time. Active: idea 1 (clock sync). See `doc/DESIGN.md` for per-feature design docs.

## Active: 1. Clock stopwatch + timer sync (GrapheneOS clock <-> PineTime)

Sync the GrapheneOS clock app (`com.android.deskclock`) stopwatch and timer with the watch: start, stop, and see both from phone and watch. Both must run in the background on the watch (keep counting/firing when not on the Timer/StopWatch screen). Open to forking and extending both the Android app and InfiniTime, with this repo as the master (git submodules).

Design doc: `doc/DESIGN-clock-sync.md`. Research done. Key findings: both run in the background on the watch already; stock Gadgetbridge carries both directions (no GB fork); the clock app must be forked (renamed package, user-installed) for real control + exact times.

Decisions (resolved): fork the clock app (renamed, user-installed) and run it as the daily clock; both stopwatch and timer; device is on Android 17 (DeskClock submodule at `deskclock/`, branch `17`); button must back out while running; reuse the existing watch StopWatch/Timer screens.

Implementation (firmware leg, on the InfiniTime submodule `clock-sync` branch, base 1.16.1):
- DONE (73916a83): remove `StopWatch::OnButtonPushed()` so the physical button backs out to the watch face while the stopwatch keeps running (Timer already did this). Pause stays on the on-screen play/pause button.
- DONE (77531c3e, build-verified): promote `Controllers::Timer` to a `main.cpp` global via a late `Init()` from DisplayApp, so a BLE service can reach it (StopWatch/Alarm already global). No behavior change.
- DONE (cea26312, build-verified): ClockSync BLE service `00070000-...` (control WRITE `00070001` + state NOTIFY `00070002`), wired through SystemTask/NimbleController; phone->watch applies to the stopwatch (new `StopWatchController::SetState`) and timer; wall-clock-referenced frames.
- DONE (51dcfbf6, build-verified): watch->phone notify from the StopWatch/Timer screen action handlers (service exposed via AppControllers). Phone commands apply directly to the controllers, so they do not echo.
- Firmware leg for clock sync is COMPLETE and build-verified (compile only; end-to-end needs the phone fork + hardware). v1 limitations: State char is NOTIFY-only (no READ); timer expiry is not notified (phone derives it from the synced target); stopwatch laps not synced.
- Build: `podman run --rm -e DISABLE_POSTBUILD=true --userns=keep-id --security-opt label=disable -v <InfiniTime>:/sources docker.io/infinitime/infinitime-build /opt/build.sh pinetime-app` (rootless podman needs `--userns=keep-id`).

Leg 2 (transport): DONE as a config runbook, `doc/clock-sync-setup.md` (Gadgetbridge BLE Intent API toggles + UUID/package filters; no code).

Leg 3 (phone bridge): code written in `phone/clocksync/` (frame codec + DataModel listeners + write-intent sender + CHARACTERISTIC_CHANGED receiver + README). Verified against the real DeskClock DataModel API and the firmware frame, but NOT built (no Android SDK here). Remaining for the user: fork the DeskClock submodule to a Gradle project with a renamed applicationId, drop in the bridge sources, add the manifest receiver, set the watch MAC, build, install, and configure Gadgetbridge per the runbook. Then OTA-flash `pinetime-mcuboot-app-dfu` and verify both directions; don't Validate the firmware until reconnect + reboot survive.

So the clock-sync feature is code-complete across all three legs; what remains is build/flash/on-device verification, which needs the hardware and the Android toolchain.
- Transport: enable both BLE Intent API toggles on the PineTime in Gadgetbridge, filter to the ClockSync UUIDs + the fork's package, reconnect + re-add to clear the GATT cache.
- Phone: fork the `deskclock/` submodule (rename package, port to Gradle), add a bridge (DataModel listeners -> CHARACTERISTIC_WRITE; CHARACTERISTIC_CHANGED receiver -> DataModel), sync full snapshots with wall-clock references.
- Build/flash firmware via the Docker image; OTA via Gadgetbridge; don't Validate until reconnect + reboot survive.

## Build / CI infrastructure

- Repos are live and public: `github.com/Tubbles/pinetime-hacks` plus the `github.com/Tubbles/InfiniTime` fork; the InfiniTime submodule points at the fork's `clock-sync` branch over HTTPS (CI-fetchable). `flake.nix` provides the Android/JDK dev shells; `.github/workflows/` has firmware.yml + phone-codec.yml. See CLAUDE.md "Building and CI".
- Firmware CI is GREEN: pushing builds the OTA DFU and uploads `pinetime-mcuboot-app-dfu` as a downloadable artifact (first run succeeded). So flashable firmware is available from CI with no local toolchain.
- Phone codec test verified locally (13 checks); its workflow runs on the next `phone/` push.
- Phone APK builds: the clock app is a BlackyHawky/Clock fork (tag 2.20, chosen because its Gradle toolchain matches the flake and it keeps the AOSP DataModel) with the ClockSync bridge, at `github.com/Tubbles/Clock@clocksync`, added as the `phone/deskclock-app` submodule. It produces a signed debug APK (`com.tubbles.deskclock.debug`), verified by building locally via `nix develop .#android`. `.github/workflows/phone-app.yml` builds it in CI and uploads `clocksync-clock-apk`. Not yet run on hardware (no device); the BLE round-trip is untested.

## Planned: 2. Scheduled brightness + silent mode

On a schedule configured entirely on the watch (no hard-coded times), switch screen brightness and toggle silent mode. Example: at 20:00 go to lowest brightness + silent; at 07:00 go to middle brightness + full noise. Configuration lives on the watch.

## Planned: 3. Wrist-raise shows a locked screen (touch rejected until button)

Waking the screen via raise-wrist should show the screen but reject touch input, like a lock screen, until the physical button is pressed to fully unlock (prevents accidental touches). Lock indicator goes in the Casio G7710 bottom-left corner (the BPM slot).

Design: `doc/DESIGN-lock-screen.md` (ready to implement). A single `locked` flag in SystemTask plus three small hooks (set on raise-from-sleep, reject touch, unlock on button) and a G7710 indicator. Decisions worth confirming before building: clear the lock when an alarm fires (recommended, so alarms can still be silenced), keep notifications view-only while locked, and accept that a raise-wake onto a non-watchface app blocks touch but shows no indicator there (v1).

## Planned: 5. Extended-Latin (åäö) in watch notifications

InfiniTime's notification font does not render Swedish characters (å, ä, ö at least), so Swedish text messages show wrong/missing glyphs. Add extended-Latin coverage to the notification font so Swedish (and similar) text renders correctly. Likely a font-generation/charset change (the notification/default font is built from a glyph range); check `src/displayapp/fonts/` and the `lv_font` generation config.

## Planned: 6. Send key tones from the phone app to the watch

User idea (needs a short clarification on exact intent): "being able to send key tones from the watch phone app". Candidate readings: DTMF/keypad tones triggered from the watch during a call, or keypress tones forwarded phone<->watch. Confirm the intended direction and use case before designing.

## Parked: next-event watch face corner (Napper)

Design: `doc/DESIGN.md`, research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Parked pending the user reaching out to Napper AB about cooperation / their thoughts. Standing finding: Napper offers no API, web client, widget, or notification carrying the time, so the only avenue was reading the app's on-screen output (ranked approaches in `doc/DESIGN.md` Leg 1). Nothing to do here until the outreach resolves.

## User written inbox
