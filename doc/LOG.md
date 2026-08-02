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

## [infinitime][build] 2026-08-02 — review findings fixed (bridge resync, screen staleness, CI guard)

- All 2026-08-02 review findings are fixed and build-verified; the SUGGESTIONS.md section is retired. The fixes: the phone bridge drops its outbound dedup cache on `ACTION_ACL_CONNECTED` so the reconnect resync can never be suppressed by a byte-identical frame (the Intent API has no delivery queue, so frames sent while the link was down were lost AND cached); inbound frames are applied only when `EXTRA_DEVICE_ADDRESS` matches the configured watch MAC (which the runbook/README now mark as required in both directions); the StopWatch/Timer screens reconcile styling and wake lock in `Refresh()` when a ClockSync command changes controller state while they are open (InfiniTime `18d02133`); `phone-app.yml` fails if the fork's `ClockSyncFrame.java` drifts from the canonical copy (diff modulo the package line).
- Both bridge copies got identical edits (canonical `phone/clocksync/`, fork commit `f16780472`); the fork rebuilt to a signed debug APK, the firmware recompiled clean via the build image.
- Threading note, kept for posterity (no action): `ClockSyncService::OnCommand` runs on the NimBLE host task and pokes `StopWatchController` fields the LVGL task reads. Worst case is one frame of torn display; upstream MusicService shares the idiom.
- Forged-broadcast caveat is now documented in the receiver header and README: the exported receiver accepts unsigned broadcasts, so a local app knowing the MAC can inject nuisance state changes. Accepted v1 risk.
- Build gotcha: a Gradle build and a fresh podman firmware build running concurrently OOM-killed the Gradle daemon (`Gradle build daemon disappeared unexpectedly`); serialized, both pass. Also, the podman build dir from the old `/sources` mount layout poisons CMakeCache for the `/repo` layout; `rm -rf InfiniTime/build` and rebuild.

## [infinitime] 2026-08-02 — wrist-raise lock screen implemented (build-verified)

- Idea 3 is implemented on the InfiniTime `clock-sync` branch, commit `49c463dc` ("Reject touch on raise-wrist wake until the button unlocks"), compile-verified via the build image. On-device verification pending, same boat as clock sync.
- The 2026-08-01 design survived an independent adversarial source re-read with every cited line intact; `doc/DESIGN-lock-screen.md` carries the corrections. The load-bearing confirmation: LVGL's indev callback only replays touch state cached by DisplayApp's `TouchEvent` handler, whose sole producer is the SystemTask push, so the single SystemTask choke point provably covers gestures, taps, AND the raw-coordinate path.
- Corrections the re-read produced: the lock flag lives solely in Settings (runtime-only, modeled on `bleRadioEnabled`; the draft's SystemTask-owned flag with a Settings mirror invited drift); timer expiry is the same silencing hazard as the alarm and is fixed by clearing the lock in the `System::Messages::GoToRunning` case (sole sender is DisplayApp's timer-expiry push, verified by grep); the button unlock block sits after the `NotifyDeviceActivity` push so the unlocking press resets the inactivity timer; the G7710 `Refresh()` must explicitly restore `heartBeat` on unlock because the ctor sets the icon only once.
- Glyph verified end to end: `shieldAlt` is U+F3ED and that codepoint is in the `jetbrains_mono_bold_20` FontAwesome range (`src/displayapp/fonts/fonts.json`, NOT the same-named `src/resources/fonts.json`), so no font regeneration was needed.
- Semantics: only raise-from-sleep locks (shake, tap, button, notification, and chime wakes come up unlocked); every entry into sleep clears the lock; a raise-wake onto a non-watchface app blocks touch but shows no indicator (accepted v1 gap).

## [infinitime] 2026-08-02 — CORRECTION: timer-expiry lock-clear must live in DisplayApp

- The previous entry said the timer-expiry hazard was fixed by clearing the lock in SystemTask's `System::Messages::GoToRunning` case. That clear could never fire: DisplayApp only pushes the message when the display is not Running (`DisplayApp.cpp:376-378`), and the wrist-raise lock can only exist while the display IS Running (raise-wake sets it, every sleep entry clears it). The scrutiny re-read's recommendation had this flaw; caught reviewing the committed diff.
- Fix (InfiniTime `a1564c62`): clear the lock at the top of DisplayApp's `TimerDone` handler, which every timer expiry passes through regardless of display state; the dead SystemTask-side clear is removed. The alarm needs no equivalent because `SetOffAlarm` always routes through SystemTask.

## [infinitime] 2026-08-02 — åäö in notifications: font range + UTF-8 truncation trim (build-verified)

- Root cause of missing Swedish glyphs: the default UI font `jetbrains_mono_bold_20` (which the Notifications screen renders with, via the theme default) covered ASCII, Cyrillic `0x410-0x44f`, and `0xB0`, but not Latin-1 Supplement. LVGL is already UTF-8 (`LV_TXT_ENC_UTF8`, `src/libs/lv_conf.h:496`) and the BLE path copies notification bytes untouched, so the font range was the only gap. The Cyrillic block was the precedent proving the mechanism.
- Fix (InfiniTime `82a60eae`): append `0xC0-0xFF` to the JetBrains Bold range in `src/displayapp/fonts/fonts.json` (64 glyphs; covers åäöÅÄÖ plus German/French/Norwegian letters). Verified: `fc-query` shows the TTF covers `0xa0-0x131`; the generated cmap contains `.range_start = 192, .range_length = 64` with Cyrillic offsets intact after it; the zero/M glyph patches still apply (generate.py hard-fails otherwise); flash grew 386644 -> 388576 B (+1932 B, 81.87% of 474632 B).
- Companion fix, same commit: `AlertNotificationService::OnAlert` caps messages at 100 bytes and could cut a multi-byte UTF-8 character in half, leaving a dangling lead byte at the end of long non-ASCII texts. A pure `LengthWithoutTruncatedUtf8` helper now trims the incomplete tail; logic verified with 13 host-compiled assertions (complete/cut 2-3-4-byte tails, malformed input, boundaries). Deliberately garbage-in-garbage-out for already-malformed input.
- Font regeneration is automatic on `fonts.json` change: the CMake custom command declares it as a dependency (`src/displayapp/fonts/CMakeLists.txt:30`); no clean needed.
- Build discipline note: builds are now strictly serialized on this machine. The earlier concurrent Gradle + firmware build OOM also dropped the user's SSH session, so one heavy build at a time, always (recorded in assistant memory too).

## [infinitime][gadgetbridge][build] 2026-08-02 — in-call DTMF key tones implemented (all legs)

- Idea 6 implemented end to end; design in `doc/DESIGN-intercom-keytones.md`, setup in `doc/clock-sync-setup.md` section 5. Firmware: InfiniTime `3b95e758` (InCall app, KeyTones service `00080001`, Settings -> Intercom, +1028 B flash, 82.09%). Hub: Clock fork `bbc17e22d`. Dialer: Tubbles/Phone `de70e473` (Fossify Phone 1.11.1 fork, submodule `phone/dialer-app`).
- Facts worth grepping later, all verified in-source 2026-08-02: Gadgetbridge tells the watch ONLY about incoming calls (`onSetCallState` drops START/END), so no call state exists watch-side and the InCall app is user-opened/user-closed; the watch ANS Reject event maps to `TelecomManager.endCall()` which ends an ONGOING call, so hang-up-from-watch works mid-call; the Intent API characteristic filter is a comma list, but the target package is single (`intent.setPackage`), hence the Clock fork forwards key-tone frames to the dialer (hub pattern); real DTMF exists only inside the default dialer's InCallService, and Fossify's `CallManager.keypad()` already implements it, so the fork is one receiver + a manifest entry + the applicationId rename.
- Fossify toolchain pin: tag 1.11.1 is the newest release still on Gradle 8 (8.13/AGP 8.11.1); `main` moved to Gradle 9.6.1. compileSdk 36 forced adding platform 36 + build-tools 36.0.0 to the flake. Trap avoided: `APP_ID` feeds BOTH `applicationId` and `namespace` in Fossify's build; only `applicationId` may be renamed (hardcoded), because renaming the namespace moves the generated R class away from the `org.fossify.phone` imports.
- OOM saga, and a standing policy change: the dialer's dex step (`mergeExtDexFossDebug`) OOM-killed the machine twice even as the only build running, once with Fossify's default `-Xmx4g` and once capped to 1536m/in-process-Kotlin/2 workers; the kill waves took out user services (dbus, pipewire) both times. The machine idles at ~6.5 GiB of 7.7 GiB used with swap full. POLICY: no local Gradle builds of the dialer on this machine; APKs come from CI (that is what it exists for). The Kotlin compile step passed locally before the dex death, so the receiver code itself is compile-verified. The Clock fork's forwarder rebuild is also delegated to CI for the same reason.
- The GB fork stays sanctioned-but-unneeded (recorded in the design doc): stock GB carries everything; a fork would only buy multi-package Intent delivery, which the 20-line hub replaces.

## [gadgetbridge][infinitime][build] 2026-08-02 — Gadgetbridge T: call state to the watch; fork labels

- The GB-fork suggestion is done: Gadgetbridge T (Tubbles/Gadgetbridge, branch `callstate`, commit `adc4e9386`, base release 0.92.2) forwards a 1-byte call flag to the new firmware characteristic `00080002` in the KeyTones service. Firmware `1bf8da27` consumes it: active -> wake + wrist-raise unlock + open InCall (skipped if already open); ended -> dismiss InCall when frontmost. Stock GB compatibility preserved on both sides (the characteristic is optional in both directions: `safeWriteToCharacteristic` no-ops on old firmware, and stock GB simply never writes it).
- Verified call lifecycle (PhoneCallReceiver): incoming answered = INCOMING -> START -> END; outgoing = OUTGOING -> END (no START!); missed/declined = INCOMING -> END. Mapping: {START, OUTGOING, ACCEPT} -> active, {END, REJECT} -> ended, INCOMING deliberately unmapped (ringing has its own watch UI; the scrutiny agent had suggested INCOMING -> active, overruled to avoid fighting the incoming-call alert). VoIP calls arrive via the NotificationListener with the same commands. `onFindDevice` synthesizes CALL_END to cancel its ring, so find-device sends a stray 0: harmless unless used mid-call.
- GB fork gotchas, all found before they bit: `addSupportedService(UUID_SERVICE_CALL)` is mandatory or characteristic discovery ignores the service forever (`getCharacteristic` returns null); side-by-side install with F-Droid GB fails with `INSTALL_FAILED_CONFLICTING_PROVIDER` unless the HARDCODED Pebble content-provider authority is renamed (applicationIdSuffix alone is not enough); the launcher label comes from `title_activity_controlcenter`, not `app_name`; both label strings are flavor-level translatable=false redirects, so literal overrides there are locale-proof.
- Fork base logistics: the GitHub Freeyourgadget mirror is archived (stale since 2024-12), so Tubbles/Gadgetbridge was created empty and received master + tag 0.92.2 + the branch from a full codeberg clone (~disk-backed scratchpad, no RAM risk). origin = the GitHub fork, upstream = codeberg.
- Toolchain: GB 0.92.2 needs Gradle 9.5.1 (repo wrapper, not nix gradle), JDK 21, build-tools 36.1.0; the flake gained a `.#gadgetbridge` shell and build-tools 36.1.0. CI `gadgetbridge.yml` builds `:app:assembleMainlineDebug` -> `gadgetbridge-t-apk`.
- Fork labels per the user's T scheme: Clock T (`fe0a46ca0`, literal manifest placeholder, locale-proof), Phone T with Swedish "Telefon T" (`c4ad5263`, locale resources; the sv name explains the user's "Telephone T" example), Gadgetbridge T (see above).

## [infinitime][gadgetbridge][ble] 2026-08-02 — first on-device session: three real bugs, all fixed

Field testing after the flash found three independent bugs. Two were Android-platform realities the design had not accounted for; one was ours.

- **Clock T crashed on watch-initiated timers (ours).** BlackyHawky's `addTimer` takes a 4th `buttonTime` argument that the timer list renders with `Long.parseLong`; the bridge passed `""`, so `TimerItem.update` threw `NumberFormatException` the moment such a timer appeared, killing the process and the bridge with it. That is what made sync look one-directional and flaky. Fixed (Clock `43bb963c8`) by using `SettingsDAO.getDefaultTimeToAddToTimer`, the same value the intent-API path passes. Lesson recorded in the canonical copy as a comment: buttonTime is a stringified number of MINUTES, never empty.
- **Outgoing calls never opened the InCall screen (platform).** Gadgetbridge derives `CALL_OUTGOING` from the `NEW_OUTGOING_CALL` broadcast (`PhoneCallReceiver.java:55-56,99-100`, registered at `DeviceCommunicationService.java:1423`), which Android 11 removed — so on Android 17 it emits nothing for phone-initiated calls, and Gadgetbridge T's forwarding had nothing to forward. Fix: the call state now originates in Phone T, whose `InCallService` sees every call (outgoing and VoIP included), and is written to the watch through Gadgetbridge's Intent API (Phone `60d5d668`). Ringing is still not reported as in-progress, so it does not fight the incoming-call screen.
- **Hang-up did nothing during an established call (platform).** The watch's ANS Reject reaches `TelecomManager.endCall()` (`GBCallControlReceiver.handleCallCmdTelecomManager`), deprecated since API 29 and evidently refused for the ongoing call; Gadgetbridge swallows both failure paths with a log line, so it fails silently. Its own deprecation note says wearable companions should use the InCallService API — which we now have. Fix: the watch also sends `'E'` on the key characteristic (InfiniTime `23e308b4`); Phone T maps it to `CallManager.reject()`, which handles ringing (`call.reject()`) and established (`call.disconnect()`) explicitly. Both paths are kept: the ANS reject still serves a ringing call answered from the notification screen.
- Diagnostic note: the DTMF keys working while hang-up failed was the decisive clue — they are different paths (keys go watch -> Clock T -> Phone T, hang-up went watch -> Gadgetbridge -> Telecom), which is what separated a platform bug from a transport bug.

## [gadgetbridge][ble] 2026-08-02 — Intent API arms at connect; GATT cache survives BT toggles

Two flashing/transport gotchas from the same session, both costing an hour each:

- **DFU failed with `GATT REQ NOT SUPPORTED` (error 6)** when enabling notifications on the DFU control point, twice. Root cause was a stale Android GATT cache: the second attempt's log showed "Services discovered" in 30 ms (cached, not re-read over the air) and a Service-Changed CCCD still set from the previous session. Toggling Bluetooth does NOT drop the cache for bonded devices; unpairing in Android's Bluetooth settings does. InfiniTime OTA has no signing anywhere (the watch validates a CRC16 only, `DfuService.cpp:421-439`), so signature/key theories are always wrong here.
- **The BLE Intent API only arms during device initialization**, i.e. at connect time. Entering the toggles and filters while the watch is already connected leaves the live connection running with the old config: nothing subscribes, nothing rebroadcasts, and the log stays completely silent (no `BleIntentApi` lines at all) while directly-wired features like call state keep working. Always disconnect/reconnect the device after touching those settings — the runbook says so for a reason.

## [infinitime][ble] 2026-08-02 — ClockSync epoch skew: the watch RTC runs on local time

The user's second field report ("sync only goes watch -> phone, stopwatch play/pause never comes back") led to a full re-audit of the phone->watch write chain. Every transport link checked out in source — Clock T's listener fires on every `StopwatchModel.setStopwatch`, the dedup cache cannot swallow a state transition, GB T registers its command receiver `RECEIVER_EXPORTED` and `addService` puts *every* discovered service's characteristics in the Intent-API map (not just supported ones, `AbstractBTLESingleDeviceSupport.java:294-297`), the hex codecs agree, and the control characteristic is plain `BLE_GATT_CHR_F_WRITE`. The real, proven bug was in the time base:

- **The frame's `reference_epoch_ms` is defined as UTC** (phone uses `System.currentTimeMillis()`), but the firmware's `NowMs()` used `DateTime::CurrentDateTime()` — and the CTS-synced RTC runs on *local* time (Gadgetbridge writes local wall clock plus a separate Local Time characteristic with tz/DST offsets, `PineTimeJFSupport.onSetTime`). At UTC+2 every phone->watch timer computed `remaining = reference - now_local` = real remaining *minus two hours* -> negative -> silent `StopTimer()`: nothing visibly happens. Watch->phone timers came out two hours too long, and a phone-started stopwatch would jump to 2:00:00. Fix: `NowMs()` now uses `DateTime::UTCDateTime()`, which subtracts those offsets; all four epoch uses (parse + both frame builders) share the helper, so one line fixed both directions. If the offsets were never written, `UTCDateTime()` degrades to the old behavior (offset 0), so stock-GB pairing is unaffected.
- The design doc even said "UTC milliseconds; InfiniTime's RTC is CTS-synced so both sides share this base" — a half-truth that caused the bug: CTS syncs the RTC to *local* time, so the shared base requires the explicit UTC conversion. Doc corrected in both places.
- Diagnostic caveat for next time: logcat captures of GB T and Clock T contain *no* application log lines (GB logs via slf4j/logback to its own file, and the bridge only logs on dropped frames), so they cannot prove whether an Intent-API write arrived. The tool for that question is GB T's own file log (Settings -> "Write log files"), which records `BLE API write` transactions and `Characteristic ... not found` errors. If phone->watch still fails after this fix, one such log of a single phone-side play press settles where the frame dies.
