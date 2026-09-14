# To Do

The repo is a personal PineTime/InfiniTime hacking playground; the feature list and statuses live in `README.md`, finished work is recorded in `doc/LOG.md` and git history. This file holds only what is outstanding or parked, and the reasoning behind those states.

## Active: lock screen v2 (user request 2026-09-14, delegated to a serial Opus subagent)

Five items, worked in order; each is closed out (firmware pushed, CI green, docs, master bump) before the next starts. Delete each item here as it lands.

1. Every wake source locks (raise, shake, tap/double tap, notification, chime, BLE-triggered) except the physical button, which wakes unlocked as today. Decision: alarm and timer expiry keep clearing the lock (their ringing screens need touch, unchanged behavior).
2. Exempt apps: Notifications (incl. preview) and InCall are fully usable while locked (touch and physical button behave normally there); the lock state persists underneath, so returning to the watch face is locked and needs the button. Flows: locked → notification → dismiss → face still locked; locked → call rings → answer by touch → InCall with keypad usable → call ends → face still locked. Removes today's incoming-call and CallStarted lock clears. Requires moving the lock's input gating from SystemTask to DisplayApp (which knows the frontmost app).
3. Settings → "Lock screen" page with a "Use lock screen" checkbox (persisted, default on). Off = no wake ever locks.
4. Settings → "Raise wrist" page exposing the four ShouldRaiseWake thresholds (roll angle 45°, stillness 56, level 384, tilt 64) with +/- steppers and a Reset-to-defaults button; persisted, appended to SettingsData without a version bump.
5. Timer app: padlock indicator while locked (raise-wake into a running timer shows the lock, button unlocks, as today minus the indicator).

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
