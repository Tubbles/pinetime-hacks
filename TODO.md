# To Do

The repo is a personal PineTime/InfiniTime hacking playground; the feature list and statuses live in `README.md`, finished work is recorded in `doc/LOG.md` and git history. This file holds only what is outstanding or parked, and the reasoning behind those states.

## Active: lock screen v2 (user request 2026-09-14, amended the same day)

Items 1 and 2 landed. The remaining ones are ordered to minimize total work rather than by number (the amendment lifted the strict order): 5, then 3, then 4 and 6 together, since those two share one slider-page implementation. Each is closed out (firmware pushed, CI green, docs, master bump) before the next starts. Delete each item here as it lands.

5. Timer app: padlock indicator while locked (a raise-wake into a running, non-ringing timer shows the lock, the button unlocks).
3. Settings → "Lock screen" page with a "Use lock screen" checkbox (persisted, default on). Off = no wake ever locks, and turning it off clears any live lock.
4. Settings → "Raise wrist" page exposing the `ShouldRaiseWake` knobs as persisted settings with `lv_slider` rows (not steppers: the level value has a 64–1024 range and nobody wants to press a button hundreds of times) and a Reset button. Six parameters: roll angle 45, stillness 56, level 384, tilt 64, plus the timing window that was fixed in the ring buffer until now — the look-back window (default 8 samples, ~800 ms at the 100 ms poll) and the settle count averaged as "now" (default 2). Needs the ring buffer to become a fixed maximum (e.g. 16) with the window and settle counts parameterized and clamped (settle strictly less than window); the shake-speed span keeps its own 8-sample constant so shake behavior does not move. Six sliders do not fit one 240x240 page, so split or scroll. Suggested ranges: roll 10–90, stillness 8–200, level 64–1024, tilt 0–256, window 3–16, settle 1–4.
6. Settings → "Lower wrist" page, same pattern (sliders, Reset, parameter struct, no MotionController dependency on Settings) for `ShouldLowerSleep`: side-tilt level 887, side-roll degrees 30, facing level 724, lower-roll degrees 30, history floor 265. Lower wrist is the sleep-on-lower trigger, not a wake source, though stock InfiniTime lists it under Wake Up. `doc/research-raise-wake.md` has no lower-wrist section yet; write one.

## Reopened: DFU reliability (user report 2026-08-11)

Flashing fails again (closed 2026-08-10 after two clean flashes; reopened by report, no cause known yet). Root-causing runs on the on-watch BLE trace, captured per the runbook section 1: BEFORE rebooting the watch (a reboot wipes the RAM ring), write 0x02 to characteristic `00080003` (nRF Connect, GB T disconnected first), page the reads out, decode with `tools/decode_trace.py`. Waiting on: the captured hex, the exact phone-side failure observable (error message / stuck point), the GB T file log of the failed attempt, and whether the watch rebooted since the failure.

## Parked: 1. Clock sync (user decision 2026-08-03)

Field verdict after the fourth on-device round: "it hardly works at all ... i dont think its useful enough of a feature for this level of issues." The code stays in the tree (firmware service, Clock T bridge, CI) but no further debugging until the user un-parks it. Next diagnostic step if resumed: GB T file log of one phone-side play press (see the runbook troubleshooting section), since the epoch fix (InfiniTime `dfbd9676`) has still never been verified as delivered on-device. Known open v1 limitations (accepted): state characteristic is NOTIFY-only (no READ); timer expiry is not notified (phone derives it); stopwatch laps not synced; a watch-initiated stopwatch run cannot seed the phone's accumulated time; InfiniTime's timer has no pause, so phone-side pause maps to stopped.

## Planned: 2. Scheduled brightness + silent mode

On a schedule configured entirely on the watch (no hard-coded times), switch screen brightness and toggle silent mode. Example: at 20:00 go to lowest brightness + silent; at 07:00 go to middle brightness + full noise. Configuration lives on the watch. Not designed yet.

## Parked: next-event watch face corner (Napper)

Design: `doc/DESIGN.md`, research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Parked pending the user reaching out to Napper AB about cooperation / their thoughts. Standing finding: Napper offers no API, web client, widget, or notification carrying the time, so the only avenue was reading the app's on-screen output (ranked approaches in `doc/DESIGN.md` Leg 1). Nothing to do here until the outreach resolves.

## User written inbox
