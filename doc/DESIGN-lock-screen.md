# Design: wrist-raise lock screen

Status: design, ready to implement. Research provenance: an InfiniTime source read on 2026-08-01 against the `clock-sync` branch (base 1.16.1); load-bearing claims are cited to `file:line` below and were verified in that pass.

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
Hook the single button funnel `SystemTask::HandleButtonAction()` (`:477-507`): while locked, the first resolved action clears the lock and is consumed (does not also act as back/sleep), modeled on the existing `fastWakeUpDone` consume pattern (`:298, 489, 506`):

```cpp
if (locked) {
  if (action != Controllers::ButtonActions::None) { locked = false; fastWakeUpDone = false; }
  return; // press only unlocks
}
```

Unlock lands ~200 ms after button release (the Click resolution delay in `ButtonHandler.cpp:44-52`). Acceptable; instant-on-press is possible with extra state but not worth the code for v1.

### 4. Where the lock state lives
`SystemTask` owns the flag (`bool locked = false;` + `bool IsLocked() const`). Also clear it in `GoToSleep()` (`:437-453`) so every entry into sleep leaves it clean — this closes the gap where a locked screen times out and is later woken by the button (that press breaks at `:300` before the unlock funnel runs).

How the face reads it — two options:
- Option B (recommended, lowest churn): store the flag as a non-persisted runtime bool in `Settings` with `SetLocked`/`IsLocked`, modeled on the runtime-only `bleRadioEnabled` (`Settings.h:308-314, 397`). Both SystemTask and the face already hold `Settings&`, so no constructor changes anywhere.
- Option A (more semantically correct, small churn): the face reads `systemTask->IsLocked()`. `AppControllers` already carries a `SystemTask*` (`Controllers.h:52`), but the G7710 `Create()`/constructor would need it added (`WatchFaceCasioStyleG7710.h:110-119`).

### 5. G7710 lock indicator
The bottom-left slot is `heartbeatIcon` + `heartbeatValue` (`WatchFaceCasioStyleG7710.cpp:151-159`); when HR is not running it is dimmed and blank (`:294-305`). Add a `Utility::DirtyValue<bool> lockedState`; in `Refresh()`, when locked set `heartbeatIcon` to a lock glyph in full color, else fall back to the existing HR logic.

Glyph: `Symbols.h` has no padlock, but `Symbols::shieldAlt` (U+F3ED) exists and is already in the default font. Recommended for v1: reuse `shieldAlt`. For a true padlock, add U+F023 to the `jetbrains_mono_bold_20` FontAwesome range (`fonts.json:10`), regenerate with `fonts/generate.py`, and add a `lock` symbol.

Coexistence: the lock glyph and the HR icon share the same slot; lock wins while locked (HR suppressed for the lock's short duration). Any future use of that corner (e.g. the parked next-event idea) must check the lock flag first. If simultaneous display is ever needed, add a separate small `lockIcon` label instead of repurposing `heartbeatIcon`.

## Implementation outline (file by file)

1. `src/systemtask/SystemTask.h` — `bool locked = false;` + `bool IsLocked() const`.
2. `src/systemtask/SystemTask.cpp` — set lock on raise-from-sleep in `UpdateMotion()`; reject touch in the `OnTouchEvent` handler; consume-and-unlock in `HandleButtonAction()`; clear in `GoToSleep()`.
3. Lock-flag storage — Option B: runtime `locked` bool in `Settings.h` (model on `bleRadioEnabled`); Option A: face gets `SystemTask*`.
4. `src/displayapp/screens/WatchFaceCasioStyleG7710.cpp` — `Refresh()` swaps `heartbeatIcon` to the lock glyph while locked; add `DirtyValue<bool> lockedState`.
5. `src/displayapp/screens/Symbols.h` — reuse `shieldAlt`, or add `lock` (U+F023).
6. Only for a true padlock: `fonts/fonts.json` + regenerate.

## Decisions (recommended defaults; adjust as you like)

- Lock-state storage: Option B, runtime Settings flag (least churn).
- Indicator glyph: reuse `shieldAlt` for v1; true padlock is optional polish.
- Alarm while locked (highest-risk interaction): an alarm wakes and loads the Alarm screen, which is silenced by touch — but touch is blocked while locked, so the user could not silence it (the button would just unlock). Recommended: clear the lock when an alarm fires (`SetOffAlarm`, `SystemTask.cpp:239-242`).
- Notification while locked: recommended to leave it view-only (lock stays; the notification preview shows but is non-interactive).
- Raise-wake onto a non-watchface app: touch is still blocked (the safety goal is met), but there is no lock indicator there (the indicator only exists on the G7710 face). Recommended: accept for v1; scoping the lock to the watch face only would need extra `currentApp` plumbing SystemTask doesn't have today.
- Shake wake stays unlocked (only raise-wrist locks).

## Open risks

- AOD: raise wakes `AODSleeping -> Running`; the lock logic applies unchanged, but verify the indicator renders across the transition.
- The alarm and non-watchface-app cases above are the two behaviors most worth deciding before implementing.
