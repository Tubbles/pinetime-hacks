# Log

Grep-able log of learnings, decisions, dead ends, and verified facts that the commits and code do not capture. Newest entries at the bottom of each tag section is fine; keep entries tagged for grep: `[init]`, `[infinitime]`, `[napper]`, `[gadgetbridge]`, `[ble]`, `[flash]`, `[build]`.

## [init] 2026-07-19 — repo created, research phase started

- Repo scaffolded (CLAUDE.md, doc/, TODO.md, SUGGESTIONS.md), conventions adapted from the zephyr-pump sibling repo.
- Watch is sealed — no SWD. All flashing must go through OTA/DFU via Gadgetbridge. Validation/rollback semantics must be understood and written down here before the first flash of self-built firmware.
- Research running on three tracks: InfiniTime internals (Casio G7710 face, BLE services, build/flash), Napper app data-extraction surface, Gadgetbridge/phone-side delivery path.

## [init] 2026-07-19 — research complete, design drafted

- InfiniTime submodule pinned to release tag 1.16.1 (latest, published 2026-07-05).
- Three research reports written to `doc/research-{infinitime,napper,gadgetbridge}.md`; synthesis in `doc/DESIGN.md`. All load-bearing claims tagged verified (URL/`file:line`) or inference.

## [gadgetbridge] 2026-07-19 — BLE Intent API removes the need for a GB fork

- Gadgetbridge 0.82.0 (Sep 2024) shipped a documented BLE Intent API: any local app broadcasts `nodomain.freeyourgadget.gadgetbridge.ble_api.commands.CHARACTERISTIC_WRITE` with device MAC + characteristic UUID + hex payload, and GB writes it over its existing watch connection. Enabled per device under Developer settings. This is the transport: no GB fork, keep the F-Droid build.
- Gotchas to design around (from FederAndInk's calendar prototype): Android's GATT service-discovery cache hides a newly added characteristic until the watch is removed and re-added in GB; large writes need MTU raised (our payload is tiny, so likely fine); no delivery queue, so re-send on GB's `BLUETOOTH_CONNECTED` broadcast.

## [infinitime] 2026-07-19 — SimpleWeatherService is the template; corner change is trivial

- The G7710 heart-rate corner is two LVGL labels; "not in use" = `HeartRateController::State() == Stopped` (currently just dims the icon). Swap in a glyph + `HH:MM` there. Watch out: InfiniTime's optional background HR measurement keeps state off `Stopped`, which would hide the event — background HR is off by default (on-device check needed).
- New BLE service modeled on SimpleWeatherService: UUID `00060000-78fc-48fe-8e23-433b3a1942d0`, one write-only characteristic, versioned payload, RAM-only `std::optional` with timestamp expiry. ~200-300 lines + ~10 wiring lines across NimbleController / SystemTask / DisplayApp / AppControllers / the face.

## [napper] 2026-07-19 — no API; the notification is the only automatic hook

- Napper has no public API, no Health Connect (Play Data Safety lists no health-data category), no calendar export, no IFTTT. GDPR export is manual-only.
- It does fire nap/bedtime notifications (verified testimonial). The decisive unknown, answerable only on the user's phone: does the notification text contain an explicit clock time, or just "nap coming up"? Everything downstream (parse -> intent -> characteristic -> corner) is designed and waiting on that answer.

## [napper] 2026-07-19 — CORRECTION: notifications carry no time; direction set

- User correction (ground truth): Napper does NOT put the next sleep/wake time in any notification. Notification-reading is not a source of the time. Lesson recorded in memory (verify-ondevice-assumptions): the design should not have leaned on an unverified on-device assumption.
- Napper offers no sanctioned integration: no public API, no web client, no Health Connect / Google Fit, no calendar/ICS export, no home-screen widget. Its Terms also restrict extracting data from the app and reverse-engineering it. See research-napper sections 2-3.
- The one place the schedule appears is the app's own screen. Home screen shows a central countdown ("First nap in 3 min", a React Native text element, so likely readable by an Android AccessibilityService) and absolute ring labels ("10:07"/"19:54", likely canvas-drawn and likely NOT readable). Reading the countdown and computing now+X gives the next-event time.
- Direction: read the app's on-screen output on a device the user controls and push the value to the watch. Leaning toward a dedicated always-on device (a cheap old phone, or an Android environment on an always-on machine — verify Play Integrity lets Napper sign in there). The watch stays paired to the carried phone, so a stationary reader must relay the value to it. See DESIGN.md Leg 1.
- Decoupling decision (DESIGN.md): build Legs 2+3 (transport + watch corner) first against a dummy source so the watch feature never blocks on Leg 1.

## [init] 2026-08-01 — Napper parked; pivot to clock sync

- Napper next-event corner parked pending the user reaching out to Napper AB about cooperation. The ToS-circumvention deliberation was scrubbed from the docs and git history earlier (branch history rewritten, reflog expired, gc pruned; no remote, nothing pushed).
- New focus, worked one idea at a time (see TODO.md): (1) sync the GrapheneOS clock app stopwatch+timer with the watch, both running in the background on the watch; (2) watch-configured scheduled brightness + silent mode; (3) wrist-raise shows a locked screen that rejects touch until the button is pressed.
- Repo is now the master for a multi-feature project; upstream trees (InfiniTime already, the Android clock app and possibly Gadgetbridge to come) are git submodules.
- Research started for idea 1: InfiniTime timer/stopwatch internals + background execution model, deskclock internals + fork/extend surface, Gadgetbridge bidirectional transport.

## [infinitime][gadgetbridge][build] 2026-08-01 — clock sync research done, design drafted

- Design: `doc/DESIGN-clock-sync.md`. Three legs: InfiniTime ClockSync BLE service (fork), stock Gadgetbridge Intent API (both directions), forked clock app.
- InfiniTime: both stopwatch and timer already run in the background (StopWatchController is a survives-navigation global; Timer fires via a FreeRTOS software timer regardless of screen; Alarm is the third precedent). The only refactor: promote `Controllers::Timer` from a DisplayApp member to a `main.cpp` global so a NimbleController-owned service can reach it (StopWatch/Alarm are already globals). No new background machinery needed.
- Gadgetbridge: the BLE Intent API carries BOTH directions. Write (phone->watch) plus subscribe+rebroadcast (watch->phone) via a second toggle. Because `addService` runs for every discovered GATT service before the "supported services" gate, a brand-new InfiniTime characteristic works with NO PineTimeJFSupport fork. Both legs since 0.82.0; per-characteristic filtering since 0.84.0. Trap: the CHANGED event extra is `EXTRA_CHARACTERISTIC` (not `_UUID`, which is the write/read command extra); the public gadgetbridge.org docs get this wrong.
- Clock app: `com.android.deskclock` is AOSP DeskClock; GrapheneOS ships an integration fork (`GrapheneOS/platform_packages_apps_DeskClock`, per-Android-version branches like `16-qpr2`). ":37" is a platform-injected build number, not a stable app version. Stock external surface is inadequate (timer create-only via SET_TIMER, no public stopwatch intent, exact times locked in a notification chronometer), so a fork is required. Can't replace the platform-signed system app: rename the package and install as a user app; upstream is Soong-only so port sources to a Gradle project. GrapheneOS: BLE Intent API toggles are a restricted setting for sideloaded apps ("Allow restricted settings" first).
- Vendor service ID: ClockSync = `00070000-...`; `0006` reserved for the parked Napper next-event service.
- Sync design: state transitions + wall-clock reference only (InfiniTime RTC is CTS-synced), full idempotent snapshots, last-writer-wins, resend-on-connect; never per-tick.

## [build][infinitime] 2026-08-01 — firmware leg started; build env notes

- Forks based on latest upstream releases: InfiniTime 1.16.1 (already the latest; work on submodule branch `clock-sync`), DeskClock branch `17`.
- Build via the official image with rootless podman: `podman run --rm -e DISABLE_POSTBUILD=true --userns=keep-id --security-opt label=disable -v <InfiniTime>:/sources docker.io/infinitime/infinitime-build /opt/build.sh pinetime-app`. Two gotchas: the image CMD is `/opt/build.sh` (pass the target as its arg, e.g. `/opt/build.sh pinetime-app`; a bare target arg replaces the CMD and fails with "executable not found"); and rootless podman needs `--userns=keep-id` or the bind-mounted `/sources/build` mkdir fails with EPERM (the InfiniTime docs assume Docker, where plain `--user` works). Toolchain (GCC, nRF SDK, mcuboot) is baked into the image, so builds are offline. `pinetime-app` + `DISABLE_POSTBUILD=true` is the fast compile-check (skips DFU zip + resources/npm).
- Editing a header mid-build gives a spurious link error (stale .o built from old sources); just re-run the incremental build.
- Done + build-verified: StopWatch button back-out (73916a83) and Timer-to-global refactor (77531c3e). ClockSync BLE service is next.

## [infinitime] 2026-08-01 — ClockSync firmware service complete (build-verified)

- ClockSync BLE service implemented on the `clock-sync` branch, bidirectional, compiles + links via `/opt/build.sh pinetime-app`. Two commits: cea26312 (service + phone->watch + notify infra) and 51dcfbf6 (watch->phone notify from the screens).
- Design decisions made during implementation:
  - Watch->phone notify is triggered from the StopWatch/Timer SCREEN action handlers (via `AppControllers.clockSyncService`, registered from SystemTask), not from inside the controllers. This is the InfiniTime-idiomatic seam (screens already call into music/weather services) and keeps the controllers decoupled from BLE.
  - No echo suppression needed: phone->watch commands apply DIRECTLY to the controllers (from the service), never through the screens, so they don't re-trigger the screen notify path.
  - Added `StopWatchController::SetState(bool running, TickType_t elapsed)` because the controller previously exposed only Start/Pause/Clear and couldn't restore a mid-run elapsed time for phone->watch sync. Timer needed no new method (StartTimer(remaining) re-arms it).
  - State characteristic is NOTIFY-only in v1 (no READ), matching MusicService. Timer expiry is not notified (the phone derives it from the synced target epoch). Laps not synced.
- Build gotcha: InfiniTime compiles with `-Werror=reorder`, so constructor init-list order must match member declaration order (tripped the Timer screen ctor once).

## [build] 2026-08-01 — Nix flake + GitHub Actions CI; DFU build details

- Modeled the build/CI on the sleipner sibling repo (Nix flake + androidenv + GitHub Actions building an APK). Nix and podman are available in the dev environment; no Android SDK/gradle or docker.
- `flake.nix` provides `devShells.android` (Android SDK 35/34 + JDK 17 + gradle_8, no NDK) and `.#jvm` (plain JDK). Firmware stays on the docker image (nRF toolchain), not Nix.
- CI: `.github/workflows/firmware.yml` (builds the DFU via the image, uploads the zip) and `.github/workflows/phone-codec.yml` (compiles + runs the pure ClockSyncFrame test, verified locally: 13 checks pass). A phone-APK workflow waits on the DeskClock Gradle port.
- Firmware DFU build gotchas (verified locally): there is no `pinetime-mcuboot-app-dfu` make target; the DFU zip is a POST_BUILD command on the `pinetime-mcuboot-app` target (`src/CMakeLists.txt:988-993`), landing at `build/src/pinetime-mcuboot-app-dfu-<ver>.zip`. `build.sh`'s `post_build.sh` collection step fails unless the recovery images are also built, so pass `DISABLE_POSTBUILD=true` (the DFU is still produced by the target) and grab the zip from `build/src/`. Mount the whole repo with `SOURCES_DIR=/repo/InfiniTime` so `git describe` resolves through the submodule gitlink and the artifact is versioned (1.16.1) rather than falling back to a default.
- To actually run CI: the repo and the InfiniTime submodule's `clock-sync` branch must be pushed to GitHub (the submodule currently points at local-only commits). Pending the user's go-ahead on creating the forks/repos.

## [build] 2026-08-01 — GitHub live, firmware CI green, phone APK builds

- Public repos created: `Tubbles/pinetime-hacks`, the `Tubbles/InfiniTime` fork (submodule -> fork `clock-sync` branch over HTTPS), and the `Tubbles/Clock` fork for the phone app. Firmware CI is green (DFU artifact downloadable).
- Phone app: chose BlackyHawky/Clock as the existing Gradle base (the AOSP DeskClock is Soong-only). Picked tag `2.20`, not `main`: 2.20 uses AGP 8.10 / Gradle 8.14 / compileSdk 35 / material 1.12, which matches the flake's android shell (SDK 35, gradle_8, JDK 17); `main` moved to AGP 9 / Gradle 9 / SDK 36 and would fight the toolchain.
- Bridge adaptation for BlackyHawky (its DataModel diverges slightly from GrapheneOS/AOSP): package `com.android.deskclock` -> `com.best.deskclock`; `StopwatchListener` is single-arg `stopwatchUpdated(after)` with no `lapAdded`; `DataModel.addTimer` takes a 4th `buttonTime` arg. The canonical `phone/clocksync/` (com.android.deskclock, GrapheneOS-targeted) is kept as the neutral reference; the fork carries the adapted copy. Regenerate the fork copy from canonical with a sed rename + those three edits.
- Build result: signed debug APK `com.tubbles.deskclock.debug` (~10 MiB), built via `nix develop .#android -c gradle assembleDebug --no-configuration-cache` (the config cache is disabled because tag 2.20's output-naming uses a deprecated variant API that conflicts with it). Verified applicationId, the ClockSyncReceiver + CHARACTERISTIC_CHANGED filter, and BLUETOOTH_CONNECT via aapt/apksigner. Not run on hardware.
- Gradle-via-nix gotcha: use the flake's `gradle` (8.14.x) not `./gradlew` (avoids the wrapper download and an exec-bit issue on the cloned gradlew).

## [infinitime] 2026-08-01 — StopWatch captures the physical button (must change)

- User requirement: pressing the button while the stopwatch/timer runs must back out to the watch face and keep it running in the background.
- Timer already complies (no button override; FreeRTOS timer keeps counting and still fires).
- StopWatch does NOT: `StopWatch::OnButtonPushed()` (`StopWatch.cpp:246-252`) pauses the stopwatch and returns true (consumes the press), so today the button pauses + stays in the app. Fix: remove the override (method + decl at `StopWatch.h:31`) so the button falls through to default back-navigation; the controller keeps counting; pause stays on the on-screen play/pause button. Holdover from when stopwatch state lived in the screen.
- Decisions locked: fork the clock app (run renamed fork as daily clock), both stopwatch + timer, device on Android 17. DeskClock submodule added at `deskclock/` (GrapheneOS/platform_packages_apps_DeskClock, branch 17, build tag 2026072900).

## [flash] 2026-07-19 — sealed-watch OTA is low-risk

- DFU writes to a secondary SPI-flash slot; bootloader swaps + runs unvalidated; reset-before-Validate rolls back; ~7 s watchdog catches a hung image; physical button gives blue=rollback / red=recovery firmware. OTA never touches the bootloader. Rule: never tap Validate until BLE reconnects and a reboot survives with the new corner working.

## [build] 2026-08-02 — phone-app CI green; clock-sync v1 reviewed

- The `Phone app` workflow's first run succeeded (3m46s including the initial Nix Android SDK fetch); `clocksync-clock-apk` is downloadable. The parallel `Firmware` re-run stayed green. Both deliverables now build from CI with no local toolchain.
- Fresh-eyes code review of the whole clock-sync v1 (firmware service + screens, phone bridge, CI): one correctness bug (the phone bridge's reconnect resync is defeated by its own outbound dedup cache) plus display-staleness and hardening notes. Findings recorded in `SUGGESTIONS.md` under "Clock-sync review findings"; nothing applied yet.
- Verified the fork's `ClockSyncFrame.java` is byte-identical to the canonical `phone/clocksync/` copy modulo the package line (drift guard candidate noted in SUGGESTIONS.md).
