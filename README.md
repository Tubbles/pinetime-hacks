# pinetime-hacks

Personal hacking playground for a PineTime smartwatch (InfiniTime firmware, Gadgetbridge companion on Android/GrapheneOS). Upstream projects are forked as git submodules with this repo as the master; the deployed Android apps follow the "T" naming scheme (Clock T, Phone T, Gadgetbridge T) and install alongside their originals.

Features:

1. **Clock sync** (parked — see `TODO.md`) — the clock app's stopwatch and timer sync with the watch in both directions, via a custom InfiniTime ClockSync BLE service and Gadgetbridge's BLE Intent API. Design: `doc/DESIGN-clock-sync.md`.
2. **Scheduled brightness + silent mode** (planned) — watch-configured schedule switches brightness and silent mode.
3. **Wrist-raise lock** (verified in the field 2026-08-10) — raise-wrist wake shows the screen but rejects touch until the button unlocks. Design: `doc/DESIGN-lock-screen.md`.
4. **Next-event corner** (parked) — Napper's next sleep/wake time in the Casio G7710 corner. Design: `doc/DESIGN.md`.
5. **åäö in notifications** (verified in the field 2026-08-10) — Latin-1 Supplement glyphs plus UTF-8-safe message truncation.
6. **In-call key tones** (verified in the field 2026-08-10) — an in-call watch app with hang-up, DTMF numberpad, and a configurable intercom key, so the intercom door opens from the watch. Design: `doc/DESIGN-intercom-keytones.md`.
7. **Disconnect warning buzz** (verified in the field 2026-08-10) — two short buzzes when the BLE connection drops, no screen wake; only in the notifications-On mode (silent and sleep modes suppress it). Buzzes 1 s after the drop (a blip that reconnects sooner stays silent) and re-arms only after 5 s of stable connection (reconnect thrash cannot buzz repeatedly).
8. **Analog 12 watch face** (verified in the field 2026-08-10) — the Analog face without a seconds hand and with numerals 1–12 around the dial.
9. **Dim notification indicator** (done) — on the G7710, Digital, and Analog 12 faces the "i" shows bright for unread notifications, dim gray when notifications are stored but read, and hides when there are none.

Start here:

- `CLAUDE.md` — repo layout, submodules, build/CI, workflow.
- `TODO.md` — current state and outstanding work.
- `doc/clock-sync-setup.md` — the runbook: installing and configuring firmware + the three apps.
- `doc/LOG.md` — the running log of findings, gotchas, and dead ends.

CI on every push builds the OTA firmware and the three app APKs as downloadable artifacts; the workflow and artifact inventory lives in `CLAUDE.md` "Building and CI".
