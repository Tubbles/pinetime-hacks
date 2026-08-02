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
- DONE (2026-08-02 review fixes, build-verified): screens restyle on phone-driven state changes (InfiniTime 18d02133); phone bridge reconnect resync no longer suppressed by its own dedup cache and inbound frames are MAC-filtered (Clock f16780472); codec drift guard in phone-app CI. See `doc/LOG.md`.
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

## Implemented, pending device verification: 3. Wrist-raise lock screen

Waking via raise-wrist shows the screen but rejects touch until the physical button unlocks (the unlocking press is consumed); shield indicator in the G7710 BPM slot while locked. Implemented on the InfiniTime `clock-sync` branch, commit `49c463dc`, compile-verified; design + verified corrections in `doc/DESIGN-lock-screen.md`, findings in `doc/LOG.md` ([infinitime] 2026-08-02).

Remaining: on-device verification together with clock sync (raise-lock, button unlock, tap/shake/button wakes stay unlocked, alarm and ringing timer clear the lock, indicator swap on the G7710 face).

## Implemented, pending device verification: 5. Extended-Latin (åäö) in watch notifications

The default UI font now carries Latin-1 Supplement (0xC0-0xFF: åäöÅÄÖ plus üßéø etc., +1932 B flash), and the Alert Notification Service trims a UTF-8 sequence cut in half by the 100-byte message cap. InfiniTime commit `82a60eae`, compile-verified; details in `doc/LOG.md` ([infinitime] 2026-08-02 åäö entry).

Remaining: on-device verification (send a Swedish text through Gadgetbridge, check å/ä/ö render on the Notifications screen and that a >100-byte message ends cleanly).

## Active: 6. In-call DTMF key tones from the watch (intercom door)

Clarified 2026-08-02. Use case: the user's intercom door calls their phone; after answering, pressing "5" opens the door. Requirements (user's words, paraphrased): the watch shows an "in call" screen that allows hanging up and opening a key-tone numberpad (a second screen with 0-9, *, #); additionally a new settings page "intercom button" selects one key to show directly on the in-call screen.

Direction: watch sends the pressed digit over BLE (new vendor characteristic, ClockSync transport pattern via Gadgetbridge's Intent API); a phone-side receiver plays the DTMF tone. Android constraint to verify: true in-band DTMF injection needs the default-dialer role, so v1 likely plays the tone acoustically (mic pickup, speakerphone) with a dialer-fork escalation if the intercom does not hear it. The user has explicitly sanctioned forking their dialer (`com.android.dialer`, the GrapheneOS AOSP Dialer fork) if needed; note AOSP Dialer is Soong-only like DeskClock was, so the escalation likely means an existing Gradle dialer with an InCallService (e.g. Fossify Phone, research pending) renamed and set as default dialer. The watch leg is identical in both cases (receiver-side swap only). Hang-up reuses the existing ANS event characteristic. Design doc pending research (in progress).

## Parked: next-event watch face corner (Napper)

Design: `doc/DESIGN.md`, research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Parked pending the user reaching out to Napper AB about cooperation / their thoughts. Standing finding: Napper offers no API, web client, widget, or notification carrying the time, so the only avenue was reading the app's on-screen output (ranked approaches in `doc/DESIGN.md` Leg 1). Nothing to do here until the outreach resolves.

## User written inbox
