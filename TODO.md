# To Do

The repo is a personal PineTime/InfiniTime hacking playground; the feature list and statuses live in `README.md`, finished work is recorded in `doc/LOG.md` and git history. This file holds only what is outstanding or parked, and the reasoning behind those states.

## Active: field verification of the implemented features

Everything below is code-complete, CI-green, and awaiting a clean on-device pass. Setup steps are in `doc/clock-sync-setup.md`.

- Disconnect warning buzz (idea 7): fires when walking out of range, no spurious buzzes mid-connection; no buzz in silent (bell Off) or Sleep mode, buzz returns in notifications-On; the buzz arrives ~1 s after the drop, a sub-second blip is silent, and a drop within 5 s of a reconnect is silent.
- Wrist-raise lock (idea 3): raise-lock and button unlock were exercised in the field 2026-08-03; still unverified: alarm, ringing timer, and ringing call clearing the lock, and the padlock indicator on the G7710, Digital, and Analog 12 (bottom-left) faces.
- åäö (idea 5): send a Swedish text through Gadgetbridge T; å/ä/ö render in the notification; a >100-byte message ends cleanly.
- Key tones (idea 6): InCall auto-opens on outgoing and answered incoming calls and closes on hang-up; digits chime in-call; hang-up from the watch works mid-call (needs the Phone T with the 'E' handling, first shipped in run `84c195a`); physical button from the keypad view returns to the in-call view (InfiniTime `a92792be`, unverified); the intercom-key setting shows the key on the main view; the door test.
- G7710 heap-starvation fix: the face's numbers render correctly again, including after leaving the face active for a while. If digits ever degrade to the built-in fallback font (readable but wrong-sized), that is the new clean failure mode — note it, it means the heap got tight again.
- Analog 12 second cut (first cut verified via field feedback 2026-08-10): hands uniformly thin, minute ticks all the way around including 11–12–1, numerals clear of the ticks, padlock bottom-left on raise-lock.
- Gadgetbridge T resilience: entering the Intent API settings + reconnect must no longer stick at "Connecting" (GB T `6f2d749ba`, unverified). If any leg stays dead, enable "Write log files" in GB T, reconnect once, and check for `failed btle action, aborting transaction`.

## Parked: 1. Clock sync (user decision 2026-08-03)

Field verdict after the fourth on-device round: "it hardly works at all ... i dont think its useful enough of a feature for this level of issues." The code stays in the tree (firmware service, Clock T bridge, CI) but no further debugging until the user un-parks it. Next diagnostic step if resumed: GB T file log of one phone-side play press (see the runbook troubleshooting section), since the epoch fix (InfiniTime `dfbd9676`) has still never been verified as delivered on-device. Known open v1 limitations (accepted): state characteristic is NOTIFY-only (no READ); timer expiry is not notified (phone derives it); stopwatch laps not synced; a watch-initiated stopwatch run cannot seed the phone's accumulated time; InfiniTime's timer has no pause, so phone-side pause maps to stopped.

## Planned: 2. Scheduled brightness + silent mode

On a schedule configured entirely on the watch (no hard-coded times), switch screen brightness and toggle silent mode. Example: at 20:00 go to lowest brightness + silent; at 07:00 go to middle brightness + full noise. Configuration lives on the watch. Not designed yet.

## Parked: next-event watch face corner (Napper)

Design: `doc/DESIGN.md`, research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Parked pending the user reaching out to Napper AB about cooperation / their thoughts. Standing finding: Napper offers no API, web client, widget, or notification carrying the time, so the only avenue was reading the app's on-screen output (ranked approaches in `doc/DESIGN.md` Leg 1). Nothing to do here until the outreach resolves.

## User written inbox
