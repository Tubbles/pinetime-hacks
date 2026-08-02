# To Do

The repo is a personal PineTime/InfiniTime hacking playground; features and their status are listed in `README.md`/`CLAUDE.md`, finished work is recorded in `doc/LOG.md` and git history. This file holds only what is outstanding.

## Active: field verification of the implemented features

Everything below is code-complete, CI-green, and (except where noted) awaiting a clean on-device pass. Setup steps are in `doc/clock-sync-setup.md`.

One-time migration first: apps installed from CI artifacts built before the stable debug keystore (`9c4afea`) cannot be updated in place — uninstall Gadgetbridge T, Clock T, and Phone T once and install fresh from the `9c4afea` (or newer) run, re-pair the watch in GB T, re-enter the Intent API settings (filter = `00070002-...` and `00080001-...` only), and re-set Phone T as default dialer.

Verification checklist, per feature:

- Clock sync (idea 1): timer and stopwatch, both directions, correct times. The epoch fix (InfiniTime `dfbd9676`) is unverified on device — before it, phone->watch timers silently did nothing and watch->phone timers ran 2 h long.
- Wrist-raise lock (idea 3): raise-lock, button unlock consumed, tap/shake/button wakes stay unlocked, alarm and ringing timer clear the lock, shield indicator on the G7710 face.
- åäö (idea 5): send a Swedish text through Gadgetbridge T; å/ä/ö render in the notification; a >100-byte message ends cleanly.
- Key tones (idea 6): InCall auto-opens on outgoing and answered incoming calls and closes on hang-up; digits chime in-call; hang-up from the watch works mid-call (needs the Phone T with the 'E' handling, first shipped in run `84c195a`); physical button from the keypad view returns to the in-call view (InfiniTime `a92792be`, unverified); the intercom-key setting shows the key on the main view; the door test.
- Gadgetbridge T resilience: entering the Intent API settings + reconnect must no longer stick at "Connecting" (GB T `6f2d749ba`, unverified). If any leg stays dead, enable "Write log files" in GB T, reconnect once, and check for `failed btle action, aborting transaction`.

Known open v1 limitations (accepted, revisit on demand): ClockSync state characteristic is NOTIFY-only (no READ); timer expiry is not notified (phone derives it); stopwatch laps not synced; a watch-initiated stopwatch run cannot seed the phone's accumulated time; InfiniTime's timer has no pause, so phone-side pause maps to stopped.

## Planned: 2. Scheduled brightness + silent mode

On a schedule configured entirely on the watch (no hard-coded times), switch screen brightness and toggle silent mode. Example: at 20:00 go to lowest brightness + silent; at 07:00 go to middle brightness + full noise. Configuration lives on the watch. Not designed yet.

## Parked: next-event watch face corner (Napper)

Design: `doc/DESIGN.md`, research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Parked pending the user reaching out to Napper AB about cooperation / their thoughts. Standing finding: Napper offers no API, web client, widget, or notification carrying the time, so the only avenue was reading the app's on-screen output (ranked approaches in `doc/DESIGN.md` Leg 1). Nothing to do here until the outreach resolves.

## User written inbox
