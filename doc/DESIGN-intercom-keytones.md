# Design: in-call DTMF key tones from the watch (intercom door)

Status: design complete, ready to implement. Research provenance: two independent adversarial source reads on 2026-08-02 — InfiniTime at `82a60eae` (branch `clock-sync`, base 1.16.1) and Gadgetbridge at upstream `a7dff08` (codeberg clone) — plus Android API verification against developer.android.com/AOSP docs. Load-bearing claims are cited `file:line` or by URL.

## Goal

The user's intercom door calls their phone. After answering (on the phone or the watch), pressing a key on the watch — typically "5" — must send that DTMF tone into the call so the door opens. Requirements, from the user:

- The watch shows an "in call" screen that allows hanging up and opening a key-tone numberpad.
- The numberpad is a second screen with all of 0-9, *, #.
- A new watch settings page, "Intercom button", selects one key that is then shown directly on the in-call screen.

## Facts that shaped the design (all verified 2026-08-02)

1. **The watch has no call state, and cannot get one from stock Gadgetbridge.** GB's `onSetCallState` acts only on `CALL_INCOMING`; `CALL_START`/`CALL_END`/everything else is silently dropped and never reaches the watch (`PineTimeJFSupport.java:461-479`). InfiniTime likewise has no in-call/ended concept; the call UI is just a notification layout inside the Notifications screen (`Notifications.cpp:314-350`), and nothing dismisses it on remote hangup. Consequence: the in-call screen is a normal launcher app (covers calls answered on the phone), auto-entered when a call is answered on the watch; it cannot auto-close when the far end hangs up.
2. **Hang-up mid-call already works over the existing channel.** The watch's ANS event characteristic `00020001-78fc-...` sends 1-byte events; `Reject = 0x00` makes GB call `TelecomManager.endCall()` (`PineTimeJFSupport.java:761-777`, `GBCallControlReceiver.java:77-93`), which ends the *foreground* call when nothing is ringing (AOSP doc). GB holds `ANSWER_PHONE_CALLS`. The watch-side send methods guard only on a live BLE connection, not on a call notification existing (`AlertNotificationService.cpp:117-141`). So "hang up" = `RejectIncomingCall()`, no new plumbing.
3. **Digits need a new characteristic.** The ANS event payload is a single enum byte with no room for keys (`AlertNotificationService.h:34`); GB has zero DTMF code. The vendor service group `0008` is free (grep of `src/components/ble/`; `0000` Music, `0001` Nav, `0002` = the ANS event char inside `0x1811`, `0003` Motion, `0005` SimpleWeather, `0006` reserved Napper, `0007` ClockSync).
4. **Stock GB carries the digits out, to ONE app.** The BLE Intent API auto-subscribes and rebroadcasts any notifiable characteristic; the characteristic filter is a comma-separated list, so ClockSync + KeyTones both fit (`BleIntentApi.java:232`, `Prefs.java:181-183`). But the target package is a single string with one `intent.setPackage()` (`BleIntentApi.java:58,147-148,233`) — GB cannot deliver to two apps. Consequence: the Clock fork (already the Intent API target, proven manifest receiver) becomes the hub and re-broadcasts key-tone frames explicitly to the dialer fork.
5. **True in-band DTMF requires the default dialer; the acoustic alternative is rejected.** `Call.playDtmfTone()` is only reachable from an `InCallService`, bound solely for the `ROLE_DIALER` holder (developer.android.com "Build a default phone app"); `TelecomManager` offers no DTMF to third parties. `ToneGenerator` explicitly does NOT generate on the uplink (AOSP class doc) — the far end could only hear it acoustically via speakerphone + mic pickup, which the phone's echo canceller is specifically built to suppress. A coin-flip by construction; rejected rather than shipped-and-hoped. The user has sanctioned a dialer fork.
6. **Fossify Phone already implements the exact hook.** GPL-3.0, Kotlin/Gradle, registered `InCallService` (`CallService.kt`), active call held by `CallManager`, and `CallManager.keypad(char)` already calls `call?.playDtmfTone(char)` + delayed `stopDtmfTone()`. The fork's only new code is a broadcast receiver. GrapheneOS allows third-party default dialers via the standard `ROLE_DIALER` flow (user-confirmed threads on discuss.grapheneos.org). This mirrors the DeskClock decision: fork a maintained Gradle app (BlackyHawky then, Fossify now) instead of porting a Soong-only AOSP app (`com.android.dialer` is far larger than DeskClock was).

## Architecture

```
watch InCall app ──1B ASCII key──▶ KeyTonesService (00080001, NOTIFY)
                                        │  Gadgetbridge (stock, Intent API,
                                        ▼  char filter = clocksync,keytones UUIDs)
                       CHARACTERISTIC_CHANGED ──▶ Clock fork (hub, manifest receiver)
                                                    │ clocksync UUID -> ClockSyncBridge
                                                    │ keytones UUID  -> explicit re-broadcast
                                                    ▼
                                       Dialer fork (Fossify Phone based, default dialer)
                                       KeyToneReceiver -> CallManager.keypad(digit)
                                       = Call.playDtmfTone -> real network DTMF -> door
```

Hang-up goes over the pre-existing path: InCall app → `AlertNotificationService::RejectIncomingCall()` → GB → `TelecomManager.endCall()`.

## Firmware leg (InfiniTime `clock-sync` branch)

1. **`KeyTonesService`** (`src/components/ble/KeyTonesService.{h,cpp}`): service `00080000-78fc-48fe-8e23-433b3a1942d0`, one NOTIFY characteristic `00080001-...`. Payload: one ASCII byte, `'0'`-`'9'`, `'*'`, `'#'` (mirrors the 1-byte ANS event convention; no frame needed for a single key). Template: `ClockSyncService` minus the write char (`ClockSyncService.cpp:174-181` Notify pattern). Wired as NimbleController member + ctor entry + `Init()` + accessor (`NimbleController.h:110`, `.cpp:49,96` pattern), exposed to apps via `AppControllers` + a `DisplayApp::Register` overload + SystemTask registration (the `clockSyncService` pattern, `SystemTask.cpp:150`).
2. **`Apps::InCall` launcher app** (`src/displayapp/screens/InCall.{h,cpp}`): registered via the `Apps.h.in` enum + `USERAPP_TYPES` in `src/displayapp/apps/CMakeLists.txt:1-20` + include in `UserApps.h` + `AppTraits` (StopWatch pattern, `StopWatch.h:64-79`); the launcher grid and `LoadScreen` default case need no edits (`DisplayApp.cpp:533-546,661-668`). Icon: `Symbols::phone` (0xf095, already in the font, `fonts.json:10`).
   - **Main view**: hang-up button (`Symbols::phoneSlash`, red) → `RejectIncomingCall()` + haptic (`MotorController::RunForDuration`); a "numberpad" button → keypad view; the configured intercom key (when set) as a large button that sends its tone directly.
   - **Keypad view**: same Screen object, widgets rebuilt in place (the `NotificationItem` precedent); `lv_btnmatrix` 3x4 map `1 2 3 / 4 5 6 / 7 8 9 / * 0 #` (LVGL 7.11 has it; Calculator is the pattern: create/map/`one_check`-less, read key via `lv_btnmatrix_get_active_btn_text` on `LV_EVENT_PRESSED`, `Calculator.cpp:36-66`). Swipe right returns to the main view (screen consumes the gesture in keypad mode only).
   - Holds a `WakeLock` while open (a call is interactive; the StopWatch precedent) and releases on destruction.
   - Each key press: notify the ASCII byte + a short haptic tick.
3. **Auto-entry on watch-answer**: in `Notifications::NotificationItem::OnCallButtonEvent` accept branch (`Notifications.cpp:361-362`), after `AcceptIncomingCall()`, start the InCall app instead of just closing (the Notifications screen's DisplayApp pointer/StartApp path; verify the exact member during implementation).
4. **`Apps::SettingIntercom` settings page** (`src/displayapp/screens/settings/SettingIntercom.{h,cpp}`): fills the one free slot in the settings menu (15/16 used, `settings/Settings.h:29-53`); entry `{Symbols::phone, "Intercom", Apps::SettingIntercom}` + an explicit `case` in `LoadScreen` (settings apps are not user apps, `DisplayApp.cpp:631-660`). UI: the same 3x4 keypad as a picker with `lv_btnmatrix_set_one_check` radio behavior, plus an "Off" control — selecting a key stores it; the setting doubles as a preview of the button the InCall screen will show.
5. **Setting storage**: `char intercomKey` (0 = off, else the ASCII key) in `SettingsData` with `SetIntercomKey`/`GetIntercomKey` (`settingsChanged` pattern), and `settingsVersion` bumped `0x000a` → `0x000b`. CAVEAT (runbook-worthy): on first boot of the new firmware the version mismatch discards the old settings file and all watch settings reset to defaults (`Settings.cpp:25-37`) — re-set watch face, wake modes, etc. once.

## Phone leg

1. **Dialer fork**: fork `github.com/FossifyOrg/Phone` → `Tubbles/Phone`, branch `keytones`, submodule `phone/dialer-app`. Rename `applicationId` to `com.tubbles.phone` (no system-app conflict — Fossify isn't preinstalled — but the rename avoids clashing with an F-Droid Fossify install; Kotlin package names stay `org.fossify.phone`, the DeskClock precedent). Add `KeyToneReceiver`: exported manifest receiver for the forwarded intent; validates the characteristic UUID (`00080001-...`), decodes the 1-byte hex payload, calls `CallManager.keypad(digit)`. `keypad` no-ops safely with no active call (`call?.` null-safe). The user sets it as default phone app (Settings → Apps → Default apps → Phone).
2. **Clock fork hub**: add a small `KeyToneForwarder` to the clocksync package — when `CHARACTERISTIC_CHANGED` arrives with the KeyTones UUID (and the configured watch MAC), re-broadcast the same extras as an explicit intent to the dialer package (a `clocksync_dialer_package` pref, default `com.tubbles.phone`). The existing `ClockSyncReceiver` keeps handling the ClockSync UUID; both share the manifest receiver entry point.
3. **Gadgetbridge config (stock)**: BLE Intent API on (both toggles, as today); characteristic filter = `00070002-...,00080001-...` (comma list); package filter stays the Clock fork. Reconnect + re-add once so the new GATT service is discovered (Android's service cache).
4. **CI**: `phone-dialer.yml` building the Fossify fork debug APK via the Nix android shell, mirroring `phone-app.yml`. Toolchain compatibility (AGP/Gradle/SDK of the chosen Fossify tag vs the flake) to be pinned during implementation, same as the BlackyHawky 2.20 selection was.

## Decisions and rejected alternatives

- **Rejected: acoustic ToneGenerator app** (no uplink API, AEC suppresses exactly this signal; would ship a coin-flip). The Fossify receiver is barely more code and is deterministic.
- **Rejected: porting `com.android.dialer`** (Soong-only, much larger than DeskClock; the Fossify fork is the same maneuver that worked for the clock).
- **Rejected for v1, adopted in v1.1: Gadgetbridge fork.** V1 shipped on stock GB (it carries everything; a fork buys nothing for DTMF). The user then asked for the call-state upgrade, so Gadgetbridge T now exists (based on 0.92.2): it writes 1/0 to the call-state characteristic `00080002` on call established/ended (`CALL_START`/`CALL_OUTGOING`/`CALL_ACCEPT` vs `CALL_END`/`CALL_REJECT`; ringing deliberately excluded — the incoming-call alert has its own UI), and the watch opens/closes the InCall screen on it. Everything still degrades gracefully to stock GB (the characteristic is simply never written; the screen is manual). Side-by-side install required renaming the hardcoded Pebble content-provider authority, not just the applicationId (`INSTALL_FAILED_CONFLICTING_PROVIDER` otherwise), and the launcher label lives in `title_activity_controlcenter`, not `app_name`. The hub forwarder stays in the Clock fork; GB still targets one package.
- Digit payload is raw ASCII (1 byte), not a versioned frame: single-purpose characteristic, mirrors the ANS event convention; a v2 can add a second characteristic if ever needed.
- Mute stays on the incoming-call preview only; the in-call screen offers hang-up + keys (scope: the user's stated flow).

## v1 limitations

- No auto-dismiss ON STOCK GADGETBRIDGE: when the far end hangs up, the InCall screen stays until the user backs out. RESOLVED in v1.1 with Gadgetbridge T (see the adopted-fork bullet above): the screen opens on call establishment and dismisses on call end, including after a watch-initiated hang-up (Reject -> endCall -> CALL_END -> dismiss).
- The InCall app is always launchable, call or no call. Harmless: digits no-op without an active call, and hang-up's `endCall()` simply returns false.
- Key presses are fire-and-forget (no delivery ack from the phone); the haptic confirms the watch sent it, not that the door heard it.

## Verification plan (hardware)

1. Flash the DFU; reconnect + re-add the watch in GB; set both toggles, char filter (both UUIDs), package filter (Clock fork). Re-do watch settings (version bump reset).
2. Install the dialer fork; set as default phone app; call the phone, answer, check the in-call screen's hang-up ends the call.
3. Set "Intercom button" to 5; have the intercom call; answer; press the 5 button on the watch's in-call screen; the door must open (this validates the whole DTMF chain).
4. Keypad screen: press digits against a DTMF decoder app or a second phone to hear tones.
