# pinetime-hacks

Personal hacking playground for a PineTime smartwatch (InfiniTime firmware, Gadgetbridge companion on Android/GrapheneOS). Upstream projects are forked as git submodules with this repo as the master; the deployed Android apps follow the "T" naming scheme (Clock T, Phone T, Gadgetbridge T) and install alongside their originals.

Features:

1. **Clock sync** (parked — see `TODO.md`) — the clock app's stopwatch and timer sync with the watch in both directions, via a custom InfiniTime ClockSync BLE service and Gadgetbridge's BLE Intent API. Design: `doc/DESIGN-clock-sync.md`.
2. **Scheduled brightness + silent mode** (planned) — watch-configured schedule switches brightness and silent mode.
3. **Lock screen** (verified in the field 2026-08-10; reworked 2026-09-14) — waking the watch shows the screen but rejects touch until the physical button unlocks it; only a button wake comes up unlocked. A notification, an incoming or ongoing call, and a ringing alarm or timer stay fully usable while locked, and the lock persists underneath them, so finishing with one lands back on a locked watch face. The whole feature switches off in Settings → Lock screen. Design: `doc/DESIGN-lock-screen.md`.
4. **Next-event corner** (parked) — Napper's next sleep/wake time in the Casio G7710 corner. Design: `doc/DESIGN.md`.
5. **åäö in notifications** (verified in the field 2026-08-10) — Latin-1 Supplement glyphs plus UTF-8-safe message truncation.
6. **In-call key tones + intercom auto-open** (key tones verified in the field 2026-08-10; auto-open done 2026-09-14) — an in-call watch app with hang-up, DTMF numberpad, and a configurable intercom key, so the intercom door opens from the watch; plus a temporary auto-open mode in Phone T, where the phone answers the next X calls from the intercom's number within Y hours and plays the door key itself, armed from an Intercom tab, a Quick Settings tile, or a home-screen widget, with an ongoing notification while it is on. Design: `doc/DESIGN-intercom-keytones.md`.
7. **Disconnect warning buzz** (verified in the field 2026-08-10) — two short buzzes when the BLE connection drops, no screen wake; only in the notifications-On mode (silent and sleep modes suppress it). Buzzes 1 s after the drop (a blip that reconnects sooner stays silent) and re-arms only after 5 s of stable connection (reconnect thrash cannot buzz repeatedly).
8. **Analog 12 watch face** (verified in the field 2026-08-10) — the Analog face without a seconds hand and with numerals 1–12 around the dial.
9. **Dim notification indicator** (done) — on the G7710, Digital, and Analog 12 faces the "i" shows bright for unread notifications, dim gray when notifications are stored but read, and hides when there are none.
10. **Secondary watch face** (done) — swipe left on the watch face to peek at Analog 12 (hardcoded); swipe right or the button returns to the primary. Runtime-only, does not survive sleep.
11. **Upcoming-alarm indicator** (done) — the Casio face shows a bell + the alarm time (24h) next to the heart-rate/padlock slot while the watch's alarm is armed and due within 24 hours; the heart-rate value takes priority over it while measuring.
12. **Wrist gesture tuning** (done) — Settings → Raise wrist and Settings → Lower wrist expose the raise-wake and lower-to-sleep detectors' thresholds as sliders with a Reset button, including the raise detector's timing window. Algorithms and knobs: `doc/research-raise-wake.md`.
13. **Error visibility** (done 2026-09-17) — rare hard errors (a DFU abort with its state and percentage, a subscription the bond store could not save, the reset reason at every boot) are appended to `/events.log` on the watch, the failures also raise a watch notification with the cause in words, and Settings → Event log shows the log newest first. Design: `doc/DESIGN-error-visibility.md`.

Start here:

- `CLAUDE.md` — repo layout, submodules, build/CI, workflow.
- `TODO.md` — current state and outstanding work.
- `doc/clock-sync-setup.md` — the runbook: installing and configuring firmware + the three apps.
- `doc/LOG.md` — the running log of findings, gotchas, and dead ends.

CI on every push builds the OTA firmware and the three app APKs as downloadable artifacts; the workflow and artifact inventory lives in `CLAUDE.md` "Building and CI".
