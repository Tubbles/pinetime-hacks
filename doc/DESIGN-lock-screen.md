# Design: lock screen

Status: implemented; current feature status is tracked in `README.md`. This file is the design record; sections 1–5 below are v1 as shipped, and the dated amendments at the end supersede them where they differ. Research provenance: an InfiniTime source read on 2026-08-01 against the `clock-sync` branch (base 1.16.1); load-bearing claims are cited to `file:line` below and were verified in that pass. Re-verified 2026-08-02 by an independent adversarial source read: every cited line held; the corrections it produced are folded in below (single-source flag in Settings, timer-expiry clear, unlock placement, glyph restore) and the touch choke point was proven complete (LVGL's indev callback only reads state cached by `DisplayApp`'s `TouchEvent` handler, whose sole producer is the SystemTask push at `SystemTask.cpp:276`; `LittleVgl.cpp:237,271-280`, `DisplayApp.cpp:406`).

## Goal

When the screen wakes via raise-wrist, show the screen but reject touch input (like a lock screen) until the physical button is pressed, which unlocks it. Waking via the button or a tap must NOT lock. (2026-08-12: shake wake joined raise-wrist — both motion wakes lock.) Show a lock indicator on the watch face in the bottom-left corner (the heart-rate/BPM slot, which is free to repurpose) — originally G7710 only; extended to the Digital face 2026-08-04.

## Why this is clean to build

InfiniTime already swallows the tap that wakes the screen (touch handled while `state != Running` is simply never forwarded — `SystemTask.cpp:277-288`, `DisplayApp.cpp:403-405`), and it already branches wake behavior per wake mode in one place. So the lock is a single boolean plus three small hooks, all in `SystemTask`, which is the only object that sees the wake reason, touch events, and button events together.

The wake reason is not carried in any message; every wake funnels through the reason-agnostic `SystemTask::GoToRunning()` (`SystemTask.cpp:409-435`), so the reason is known only at each call site. That is exactly what we want: the raise-wrist wake has a unique call site.

## Design

### 1. Set the lock on motion wake (raise or shake)
Raise and shake wakes are OR'd into one `GoToRunning()` in `SystemTask::UpdateMotion()` (`SystemTask.cpp:463-470`). Capture `IsSleeping()` before waking and set `locked = true` when a motion wake fired from sleep (v1 locked only the raise branch; shake joined 2026-08-12 by user decision — both motion gestures share the accidental-trigger problem):

```cpp
bool raiseWake = isWakeUpModeOn(RaiseWrist) && motionController.ShouldRaiseWake();
bool shakeWake = isWakeUpModeOn(Shake) && motionController.CurrentShakeSpeed() > GetShakeThreshold();
if (raiseWake || shakeWake) {
  bool wasSleeping = IsSleeping();
  GoToRunning();
  if (wasSleeping) locked = true;
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

### 5. Watch-face lock indicator (G7710 + Digital + Analog 12)
The bottom-left slot is `heartbeatIcon` + `heartbeatValue` (same layout on both faces; G7710: `WatchFaceCasioStyleG7710.cpp:151-159`, dimmed and blank when HR is not running, `:294-305`). Add a `Utility::DirtyValue<bool> lockedState`; in `Refresh()`, when locked set `heartbeatIcon` to the lock glyph in full color, else fall back to the existing HR logic. The Digital face mirrors the same pattern (added 2026-08-04); its padlock paints white, matching that face's default text color. The Analog 12 face (added 2026-08-10) has no heart-rate slot, so it shows the padlock in its own bottom-left label, blank while unlocked.

Glyph: `Symbols::lock` (fa-lock U+F023, sourced 2026-08-04 into the FontAwesome range of `jetbrains_mono_bold_20` — `src/displayapp/fonts/fonts.json:10`; note the full path, the same-named `src/resources/fonts.json` holds only watch-face fonts). That is the font the labels actually use: `heartbeatIcon` has no font override, so it renders with `theme.font_normal = jetbrains_mono_bold_20` (`InfiniTimeTheme.cpp:222`). v1 shipped with `Symbols::shieldAlt` (U+F3ED) because it was already in the font; the padlock replaced it on both faces once the glyph was added (fonts regenerate automatically at build time via `fonts/generate.py`).

(2026-09-14: the Timer app joined the indicator list, see v2.5 below.)

Restore on unlock: the ctor sets `heartbeatIcon` to `Symbols::heartBeat` once (`:152`), so `Refresh()` must not only swap in the lock glyph while locked but also explicitly restore `heartBeat` when the lock clears — otherwise the padlock sticks until the screen is recreated.

Coexistence: the lock glyph and the HR icon share the same slot; lock wins while locked (HR suppressed for the lock's short duration). Any future use of that corner (e.g. the parked next-event idea) must check the lock flag first. If simultaneous display is ever needed, add a separate small `lockIcon` label instead of repurposing `heartbeatIcon`.

## Implementation outline (file by file)

1. `src/components/settings/Settings.h` — runtime-only `bool locked = false;` (outside `SettingsData`, next to `bleRadioEnabled`) + `SetLocked`/`IsLocked`.
2. `src/systemtask/SystemTask.cpp` — set lock on raise-from-sleep in `UpdateMotion()`; reject touch in the `OnTouchEvent` handler; consume-and-unlock in `HandleButtonAction()` (after the `NotifyDeviceActivity` push); clear in `GoToSleep()` and in `SetOffAlarm`.
3. `src/displayapp/DisplayApp.cpp` — clear the lock in the `TimerDone` handler (timer expiry, see Decisions).
3. `src/displayapp/screens/WatchFaceCasioStyleG7710.{h,cpp}` and `WatchFaceDigital.{h,cpp}` — add `DirtyValue<bool> lockedState`; `Refresh()` swaps `heartbeatIcon` to `Symbols::lock` while locked and restores `heartBeat` when it clears.
4. `src/displayapp/screens/Symbols.h` — `lock` = U+F023, backed by the codepoint in `src/displayapp/fonts/fonts.json` (fonts regenerate at build time).

## Decisions (recommended defaults; adjust as you like)

- Lock-state storage: single runtime Settings flag (see section 4).
- Indicator glyph: v1 reused `shieldAlt` (already in the font); replaced by a true padlock (`Symbols::lock`, U+F023) on 2026-08-04.
- Alarm while locked (highest-risk interaction): an alarm wakes and loads the Alarm screen, dismissable by touch or by the physical button (`Alarm.cpp:142-147, 171-181`) — but while locked, touch is blocked AND the button is consumed by the unlock funnel, so both routes are dead. Clear the lock when an alarm fires (`SetOffAlarm`, `SystemTask.cpp:239-242`).
- Timer expiry while locked (same hazard, found in the 2026-08-02 re-read): a ringing timer is silenced by touch or by backing out with the button — both blocked while locked (the buzzing does auto-stop after 10 s, `Timer.cpp:128-132`, so it is milder than the alarm). Clear the lock in DisplayApp's `TimerDone` handler (`DisplayApp.cpp:375`), NOT in SystemTask's `Messages::GoToRunning` case: DisplayApp only pushes that message when the display is not Running (`DisplayApp.cpp:376-378`), and the lock can only exist while the display IS Running (raise-wake set it, sleep entry clears it), so a SystemTask-side clear would never fire in the one window that matters. The re-read's original recommendation had exactly this flaw; caught in post-implementation review.
- Notification while locked: view-only (lock stays; the preview shows but is non-interactive). EXCEPTION, found in the field 2026-08-03: an INCOMING CALL notification clears the lock — it is interactive (answer/reject), and the CallStarted unlock cannot save it because answering is what triggers CallStarted. Same hazard class as the alarm and the ringing timer; this was its missed third instance. Note the converse for consistency: a notification or chime that itself wakes the screen comes up UNLOCKED (`SystemTask.cpp:231-237, 344-359` never set the lock) — only motion wakes lock, and those wakes are not motion wakes.
- Raise-wake onto a non-watchface app: touch is still blocked (the safety goal is met), but there is no lock indicator there (the indicator only exists on the G7710, Digital, and Analog 12 faces). Accept for v1; scoping the lock to the watch face only would need extra `currentApp` plumbing SystemTask doesn't have today. (DisplayApp only resets Launcher/Notifications/QuickSettings/Settings to Clock on sleep, `DisplayApp.cpp:326-332`; other apps persist and can be raise-woken into.)
- Shake wake locks like raise-wrist since 2026-08-12 (v1 kept it unlocked); tap, button, and notification wakes stay unlocked.

## Residual notes (verified, accepted)

- AOD: `IsSleeping()` is true in `AODSleeping` (`SystemTask.h:100-102`), so a raise from AOD locks as intended; the indicator paints normally once Running.
- Touch suppression precision: skipping `ProcessTouchInfo` freezes `TouchHandler::IsTouching()` at its last value rather than forcing false. That last value is effectively always false (the pre-sleep touch ended in a release), so DisplayApp's continuous raw-coordinate path (`DisplayApp.cpp:494-496`) stays dormant; only raw-handler apps (InfiniPaint/Paddle) would ever care, and only if raise-woken mid-touch — accepted.
- BLE-triggered loads while locked (pairing PassKey, firmware update, `SystemTask.cpp:248-251, 370-372`): wake unlocked or appear touch-blocked if a lock is live; both are informational screens, harmless.

## Amendment 2026-09-14 (v2): the lock belongs to the wake, not to the gesture

User request, delivered as a five-item package. This section records each item as it lands; where it contradicts sections 1–5 above, this section is current.

### v2.1 — every wake source locks, except the physical button

The v1 rule ("motion wakes lock, everything else does not") was a statement about which gesture is accidental. It turned out to be the wrong axis: a pocket tap, an arriving notification, the hourly chime and a BLE-driven screen load all put a live touchscreen in front of a sleeve just as readily as a wrist raise does. The rule is now about the transition: **any sleep → running transition locks; the physical button is the one deliberate wake and comes up unlocked.**

Implementation consequence: the set moves from the motion branch in `UpdateMotion()` into `SystemTask::GoToRunning()` itself, after the early `state == Running` return, so it fires exactly once per real wake and no call site needs to know it exists. `IsSleeping()` is `state != Running` (`SystemTask.h`), so "the body of `GoToRunning` ran" and "we were sleeping" are the same predicate — no `wasSleeping` capture is needed any more.

Every pre-existing lock clear had to move to *after* its `GoToRunning()` call, because the wake now sets what they clear. That ordering fix shipped with this item and was then made mostly moot by v2.2, which replaced the clears with app exemptions; what survives is the button wake. The fast-wake path in `Messages::HandleButtonEvent` clears the lock right after its `GoToRunning()`. This is the only exemption by wake source, and it is expressed as a clear rather than a flag so `GoToRunning` stays reason-agnostic.

Falls out of the rule rather than being chosen: `Messages::OnChargingEvent` and a wake-lock acquisition (`Messages::DisableSleeping`) also transition out of sleep, so they lock too. Neither screen needs touch, so this is harmless. `GoToSleep()` still clears the lock on every sleep entry.

The lock is set before the `GoToRunning` message is pushed to DisplayApp, so the watch face cannot paint one unlocked frame before the flag arrives.

### v2.2 — the gate moves to DisplayApp, and some apps are exempt

The v1 gate lived in `SystemTask`, which was the right place as long as the rule was "no touch at all". It is the wrong place for "no touch except on these screens", because `SystemTask` has no idea which app is frontmost: `currentApp` is DisplayApp's. So the touch gate and the button unlock-consume move into `DisplayApp`, while the flag itself stays where it was, in `Settings`, still with a single owner.

Exempt while locked (`DisplayApp::IsInputLocked()`):

- `Apps::Notifications` and `Apps::NotificationsPreview`. An incoming call's answer and reject buttons live on the notification screen, so this is what makes a locked watch answerable.
- `Apps::InCall`, including the DTMF keypad and the physical-button back-out from it.
- `Apps::Alarm` while `alarmController.IsAlerting()`, and `Apps::Timer` while its `GetTimerState()` reports expired.

Everything else is gated, and the lock persists across the visit: dismissing a notification or silencing a ringing alarm lands back on a locked watch face, which is the point. Outside their ringing states Alarm and Timer are ordinary locked apps, so a raise-wake into a running timer is locked and shows the padlock (v2.5).

**This supersedes the v1 "Decisions" entries for the alarm, the ringing timer and the incoming call, and the v2.1 ordering work that went with them.** Those three were handled by clearing the lock, which meant a ringing alarm at 3 am left the watch unlocked afterwards. User decision 2026-09-14: they are dismissable under the lock instead, and the lock survives. Nothing clears the lock any more except a button press (consumed by the unlock), sleep entry, and turning the feature off (v2.3).

Two things broke when the `SystemTask` gate was removed, both because the touch panel is read on every event again instead of being skipped while locked:

- `TouchHandler::IsTouching()` is live rather than frozen false. The v1 "Touch suppression precision" residual note leaned on it being frozen to keep DisplayApp's continuous raw-coordinate path (`currentScreen->OnTouchEvent(x, y)` at the bottom of `Refresh()`) dormant. That reasoning is void, so the path got its own lock check; without it InfiniPaint and Paddle would draw through the lock.
- The pending gesture in `TouchHandler` is only cleared by `GestureGet()`. Dropping the event without calling it leaves, say, a `SwipeUp` made under the lock sitting in the handler, ready to fire on the first touch after unlocking (a plain press reports gesture `None`, so it does not overwrite). The gate calls `GestureGet()` and throws the result away.

The gate sits before `lvgl.SetNewTouchPoint()`, still the only writer of the state LVGL's indev callback reads, so LVGL sees no press and a locked screen dims and sleeps on the normal inactivity timeout exactly as in v1. The unlocking press still resets that timeout: `SystemTask::HandleButtonAction` pushes `NotifyDeviceActivity` before dispatching the action, and only the action is consumed.

### v2.5 — the padlock reaches the Timer app

The indicator was watch-face only, which the v1 decisions list accepted ("raise-wake onto a non-watchface app: touch is still blocked, but there is no lock indicator there"). The Timer is the one app where that gap bites: the watch falls asleep on a running timer, and a raise puts that screen straight back in front of you with no explanation for why it ignores taps.

Layout, verified rather than assumed: the top-left corner is **not** free. `minuteCounter` is aligned `LV_ALIGN_IN_TOP_LEFT` and `secondCounter` `LV_ALIGN_IN_TOP_RIGHT`, each about 100 px wide (`std::max(number width + 10, 58)` at `jetbrains_mono_76`) and ~186 px tall, and the bottom 50 px is the start/pause button. The free space is the ~38 px strip between the two counters; the colon label below it is centered at y offset -29, so its top edge clears y=0 comfortably. The padlock goes `LV_ALIGN_IN_TOP_MID`. `Counter::HideControls()` only hides the +/- buttons without resizing the container, so this holds in both running and stopped states.

It is driven by plain `settingsController.IsLocked()`, not by `DisplayApp::IsInputLocked()`. It therefore shows while the timer rings, which is a state that accepts touch. Deliberate: it means what it means on a watch face, the watch is locked underneath and will be locked again the moment the ringing is dealt with, and the alternative puts a second copy of the exemption rule inside a screen where it can drift.

### v2.3 — "Use lock screen", and the rule for appending to SettingsData

`lockScreenEnabled` (default true) gates the set in `GoToRunning()`. Turning it off also clears a live lock, and that clear lives in `Settings::SetLockScreenEnabled` rather than in the screen, so it holds however the setting is reached.

The persistence rule this project follows, and the trap in it, is worth stating once because items 2.4 and 2.6 append eleven more fields under it:

- `settingsVersion` is **not** bumped. A bump makes `LoadSettingsFromFile` reject the stored file and reset every setting on the watch, which is far worse than a new field starting at its default.
- `LoadSettingsFromFile` reads `sizeof(settings)` bytes into a **default-constructed local** `SettingsData` and copies the whole thing in if the version matches. Bytes the (shorter) file does not reach therefore keep their NSDMI defaults. That is what makes appending safe.
- **The trap**: "appended at the end" is not the same as "outside the old file". A `bool` appended after the previous last member (`char intercomKey`) is laid out in the previous layout's *trailing padding*, which is inside the old file's length, so it loads whatever byte that file happens to carry there instead of its default. `alignas(4)` on the first appended member avoids this: `SettingsData` is 4-byte aligned (it starts with `uint32_t version`), so the first 4-aligned free offset is exactly the previous `sizeof`, past every byte an older `settings.dat` holds. Everything appended after that first member is safely beyond it too.

The page is a single `lv_checkbox`, modeled on `SettingWakeUp`, with `Symbols::lock` as its icon. It takes the first slot of a fifth settings page (`nScreens` 4 → 5); `List` skips `Apps::None` entries, so the unused slots render as nothing until the motion pages fill them.
