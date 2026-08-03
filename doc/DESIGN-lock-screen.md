# Design: wrist-raise lock screen

Status: implemented; current feature status is tracked in `README.md`. This file is the design record. Research provenance: an InfiniTime source read on 2026-08-01 against the `clock-sync` branch (base 1.16.1); load-bearing claims are cited to `file:line` below and were verified in that pass. Re-verified 2026-08-02 by an independent adversarial source read: every cited line held; the corrections it produced are folded in below (single-source flag in Settings, timer-expiry clear, unlock placement, glyph restore) and the touch choke point was proven complete (LVGL's indev callback only reads state cached by `DisplayApp`'s `TouchEvent` handler, whose sole producer is the SystemTask push at `SystemTask.cpp:276`; `LittleVgl.cpp:237,271-280`, `DisplayApp.cpp:406`).

## Goal

When the screen wakes via raise-wrist, show the screen but reject touch input (like a lock screen) until the physical button is pressed, which unlocks it. Waking via the button or a tap must NOT lock. Show a lock indicator on the Casio G7710 face, in the bottom-left corner (the heart-rate/BPM slot, which is free to repurpose).

## Why this is clean to build

InfiniTime already swallows the tap that wakes the screen (touch handled while `state != Running` is simply never forwarded — `SystemTask.cpp:277-288`, `DisplayApp.cpp:403-405`), and it already branches wake behavior per wake mode in one place. So the lock is a single boolean plus three small hooks, all in `SystemTask`, which is the only object that sees the wake reason, touch events, and button events together.

The wake reason is not carried in any message; every wake funnels through the reason-agnostic `SystemTask::GoToRunning()` (`SystemTask.cpp:409-435`), so the reason is known only at each call site. That is exactly what we want: the raise-wrist wake has a unique call site.

## Design

### 1. Set the lock only on raise-wrist wake
Raise and shake wakes are OR'd into one `GoToRunning()` in `SystemTask::UpdateMotion()` (`SystemTask.cpp:463-470`). Split the condition, capture `IsSleeping()` before waking, and set `locked = true` only when the raise branch fired from sleep:

```cpp
bool raiseWake = isWakeUpModeOn(RaiseWrist) && motionController.ShouldRaiseWake();
bool shakeWake = isWakeUpModeOn(Shake) && motionController.CurrentShakeSpeed() > GetShakeThreshold();
if (raiseWake || shakeWake) {
  bool wasSleeping = IsSleeping();
  GoToRunning();
  if (wasSleeping && raiseWake) locked = true;
}
```

Touch-wake (`SystemTask.cpp:280-287`) and button-wake (`:296-301`) never touch `locked`, so tap-to-wake and button-wake come up unlocked with no extra plumbing.

### 2. Reject touch while locked
Choke point: the `Messages::OnTouchEvent` handler in SystemTask (`:270-289`). Add, before the `ProcessTouchInfo` call at `:272`:

```cpp
if (state == SystemTaskState::Running && locked) break;
```

Skipping `ProcessTouchInfo` leaves `TouchHandler::IsTouching()` false, which disables both the gesture path (`SystemTask.cpp:276`) and the continuous raw-coordinate path (`DisplayApp.cpp:494-496`) — important because a raise-wake can land on a non-watchface app whose raw handler is live. Skipping the I2C read is safe: the touch IRQ is edge-triggered and re-asserts on the next touch. Swallowed touches never call `lv_disp_trig_activity`, so a locked screen still dims and sleeps on the normal timeout (a stray wrist-raise self-clears) — desirable, no change needed.

### 3. Unlock on button press
Hook the single button funnel `SystemTask::HandleButtonAction()` (`:477-507`): while locked, the first resolved action clears the lock and is consumed (does not also act as back/sleep). All action types (Click, DoubleClick, LongPress, LongerPress) resolve through this funnel (`ButtonHandler.cpp:35-73`), so the consume catches every one. Place the block AFTER the `displayApp.PushMessage(NotifyDeviceActivity)` at `:482`, so the unlock press also resets the dim/sleep inactivity timer (otherwise a near-timeout locked screen could sleep right after unlocking):

```cpp
if (settingsController.IsLocked()) {
  if (action != Controllers::ButtonActions::None) {
    settingsController.SetLocked(false);
  }
  return; // press only unlocks
}
```

(No `fastWakeUpDone` juggling: it can only be true after a button wake, which never locks, so the combination is unreachable.) Unlock lands ~200 ms after button release (the Click resolution delay in `ButtonHandler.cpp:44-52`). Acceptable; instant-on-press is possible with extra state but not worth the code for v1.

### 4. Where the lock state lives
A single non-persisted runtime bool in `Settings` (`SetLocked`/`IsLocked`), modeled on the runtime-only `bleRadioEnabled` (`Settings.h:308-314, 397` — declared outside `SettingsData`, and persistence writes only the struct via `sizeof(settings)`, `Settings.cpp:32,45`, so it is provably never saved). Settings is the sole owner: SystemTask sets/clears it through its existing `settingsController`, the face reads it through its own — one source of truth, no constructor changes anywhere. (An earlier draft had SystemTask own the flag with Settings as a mirror; that invites drift and was dropped.)

Clear it in `GoToSleep()` (`:437-453`) so every entry into sleep leaves it clean — this closes the gap where a locked screen times out and is later woken by the button: that press breaks at `:300` before the unlock funnel runs, so without the sleep-clear a button-wake would come up locked. The same function covers AOD entry.

### 5. G7710 lock indicator
The bottom-left slot is `heartbeatIcon` + `heartbeatValue` (`WatchFaceCasioStyleG7710.cpp:151-159`); when HR is not running it is dimmed and blank (`:294-305`). Add a `Utility::DirtyValue<bool> lockedState`; in `Refresh()`, when locked set `heartbeatIcon` to a lock glyph in full color, else fall back to the existing HR logic.

Glyph: `Symbols.h` has no padlock, but `Symbols::shieldAlt` (U+F3ED, `Symbols.h:11`) exists and is confirmed present in the font the label actually uses: `heartbeatIcon` has no font override, so it renders with `theme.font_normal = jetbrains_mono_bold_20` (`InfiniTimeTheme.cpp:222`), whose FontAwesome range ends `..., 0xf1ec, 0xf55a, 0xf3ed` (`src/displayapp/fonts/fonts.json:10` — note the full path; the same-named `src/resources/fonts.json` holds only watch-face fonts). Recommended for v1: reuse `shieldAlt`. For a true padlock, add U+F023 to that range, regenerate with `fonts/generate.py`, and add a `lock` symbol.

Restore on unlock: the ctor sets `heartbeatIcon` to `Symbols::heartBeat` once (`:152`), so `Refresh()` must not only swap in `shieldAlt` while locked but also explicitly restore `heartBeat` when the lock clears — otherwise the shield sticks until the screen is recreated.

Coexistence: the lock glyph and the HR icon share the same slot; lock wins while locked (HR suppressed for the lock's short duration). Any future use of that corner (e.g. the parked next-event idea) must check the lock flag first. If simultaneous display is ever needed, add a separate small `lockIcon` label instead of repurposing `heartbeatIcon`.

## Implementation outline (file by file)

1. `src/components/settings/Settings.h` — runtime-only `bool locked = false;` (outside `SettingsData`, next to `bleRadioEnabled`) + `SetLocked`/`IsLocked`.
2. `src/systemtask/SystemTask.cpp` — set lock on raise-from-sleep in `UpdateMotion()`; reject touch in the `OnTouchEvent` handler; consume-and-unlock in `HandleButtonAction()` (after the `NotifyDeviceActivity` push); clear in `GoToSleep()` and in `SetOffAlarm`.
3. `src/displayapp/DisplayApp.cpp` — clear the lock in the `TimerDone` handler (timer expiry, see Decisions).
3. `src/displayapp/screens/WatchFaceCasioStyleG7710.{h,cpp}` — add `DirtyValue<bool> lockedState`; `Refresh()` swaps `heartbeatIcon` to `shieldAlt` while locked and restores `heartBeat` when it clears.
4. `src/displayapp/screens/Symbols.h` — no change (reuse `shieldAlt`); only a true padlock needs `src/displayapp/fonts/fonts.json` + regenerate.

## Decisions (recommended defaults; adjust as you like)

- Lock-state storage: single runtime Settings flag (see section 4).
- Indicator glyph: reuse `shieldAlt` for v1; true padlock is optional polish.
- Alarm while locked (highest-risk interaction): an alarm wakes and loads the Alarm screen, dismissable by touch or by the physical button (`Alarm.cpp:142-147, 171-181`) — but while locked, touch is blocked AND the button is consumed by the unlock funnel, so both routes are dead. Clear the lock when an alarm fires (`SetOffAlarm`, `SystemTask.cpp:239-242`).
- Timer expiry while locked (same hazard, found in the 2026-08-02 re-read): a ringing timer is silenced by touch or by backing out with the button — both blocked while locked (the buzzing does auto-stop after 10 s, `Timer.cpp:128-132`, so it is milder than the alarm). Clear the lock in DisplayApp's `TimerDone` handler (`DisplayApp.cpp:375`), NOT in SystemTask's `Messages::GoToRunning` case: DisplayApp only pushes that message when the display is not Running (`DisplayApp.cpp:376-378`), and the lock can only exist while the display IS Running (raise-wake set it, sleep entry clears it), so a SystemTask-side clear would never fire in the one window that matters. The re-read's original recommendation had exactly this flaw; caught in post-implementation review.
- Notification while locked: view-only (lock stays; the preview shows but is non-interactive). Note the converse for consistency: a notification or chime that itself wakes the screen comes up UNLOCKED (`SystemTask.cpp:231-237, 344-359` never set the lock) — only raise-wrist locks, and those wakes are not raise-wrist.
- Raise-wake onto a non-watchface app: touch is still blocked (the safety goal is met), but there is no lock indicator there (the indicator only exists on the G7710 face). Accept for v1; scoping the lock to the watch face only would need extra `currentApp` plumbing SystemTask doesn't have today. (DisplayApp only resets Launcher/Notifications/QuickSettings/Settings to Clock on sleep, `DisplayApp.cpp:326-332`; other apps persist and can be raise-woken into.)
- Shake wake stays unlocked (only raise-wrist locks).

## Residual notes (verified, accepted)

- AOD: `IsSleeping()` is true in `AODSleeping` (`SystemTask.h:100-102`), so a raise from AOD locks as intended; the indicator paints normally once Running.
- Touch suppression precision: skipping `ProcessTouchInfo` freezes `TouchHandler::IsTouching()` at its last value rather than forcing false. That last value is effectively always false (the pre-sleep touch ended in a release), so DisplayApp's continuous raw-coordinate path (`DisplayApp.cpp:494-496`) stays dormant; only raw-handler apps (InfiniPaint/Paddle) would ever care, and only if raise-woken mid-touch — accepted.
- BLE-triggered loads while locked (pairing PassKey, firmware update, `SystemTask.cpp:248-251, 370-372`): wake unlocked or appear touch-blocked if a lock is live; both are informational screens, harmless.
