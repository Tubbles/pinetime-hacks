# Log

Grep-able log of learnings, decisions, dead ends, and verified facts that the commits and code do not capture. Newest entries at the bottom of each tag section is fine; keep entries tagged for grep: `[init]`, `[infinitime]`, `[napper]`, `[gadgetbridge]`, `[ble]`, `[flash]`, `[build]`.

## [init] 2026-07-19 — repo created, research phase started

- Repo scaffolded (CLAUDE.md, doc/, TODO.md, SUGGESTIONS.md), conventions adapted from the zephyr-pump sibling repo.
- Watch is sealed — no SWD. All flashing must go through OTA/DFU via Gadgetbridge. Validation/rollback semantics must be understood and written down here before the first flash of self-built firmware.
- Research running on three tracks: InfiniTime internals (Casio G7710 face, BLE services, build/flash), Napper app data-extraction surface, Gadgetbridge/phone-side delivery path.

## [init] 2026-07-19 — research complete, design drafted

- InfiniTime submodule pinned to release tag 1.16.1 (latest, published 2026-07-05).
- Three research reports written to `doc/research-{infinitime,napper,gadgetbridge}.md`; synthesis in `doc/DESIGN.md`. All load-bearing claims tagged verified (URL/`file:line`) or inference.

## [gadgetbridge] 2026-07-19 — BLE Intent API removes the need for a GB fork

- Gadgetbridge 0.82.0 (Sep 2024) shipped a documented BLE Intent API: any local app broadcasts `nodomain.freeyourgadget.gadgetbridge.ble_api.commands.CHARACTERISTIC_WRITE` with device MAC + characteristic UUID + hex payload, and GB writes it over its existing watch connection. Enabled per device under Developer settings. This is the transport: no GB fork, keep the F-Droid build.
- Gotchas to design around (from FederAndInk's calendar prototype): Android's GATT service-discovery cache hides a newly added characteristic until the watch is removed and re-added in GB; large writes need MTU raised (our payload is tiny, so likely fine); no delivery queue, so re-send on GB's `BLUETOOTH_CONNECTED` broadcast.

## [infinitime] 2026-07-19 — SimpleWeatherService is the template; corner change is trivial

- The G7710 heart-rate corner is two LVGL labels; "not in use" = `HeartRateController::State() == Stopped` (currently just dims the icon). Swap in a glyph + `HH:MM` there. Watch out: InfiniTime's optional background HR measurement keeps state off `Stopped`, which would hide the event — background HR is off by default (on-device check needed).
- New BLE service modeled on SimpleWeatherService: UUID `00060000-78fc-48fe-8e23-433b3a1942d0`, one write-only characteristic, versioned payload, RAM-only `std::optional` with timestamp expiry. ~200-300 lines + ~10 wiring lines across NimbleController / SystemTask / DisplayApp / AppControllers / the face.

## [napper] 2026-07-19 — no API; the notification is the only automatic hook

- Napper has no public API, no Health Connect (Play Data Safety lists no health-data category), no calendar export, no IFTTT. GDPR export is manual-only.
- It does fire nap/bedtime notifications (verified testimonial). The decisive unknown, answerable only on the user's phone: does the notification text contain an explicit clock time, or just "nap coming up"? Everything downstream (parse -> intent -> characteristic -> corner) is designed and waiting on that answer.

## [napper] 2026-07-19 — CORRECTION: notifications carry no time; direction set

- User correction (ground truth): Napper does NOT put the next sleep/wake time in any notification. Notification-reading is not a source of the time. Lesson recorded in memory (verify-ondevice-assumptions): the design should not have leaned on an unverified on-device assumption.
- Napper offers no sanctioned integration: no public API, no web client, no Health Connect / Google Fit, no calendar/ICS export, no home-screen widget. Its Terms also restrict extracting data from the app and reverse-engineering it. See research-napper sections 2-3.
- The one place the schedule appears is the app's own screen. Home screen shows a central countdown ("First nap in 3 min", a React Native text element, so likely readable by an Android AccessibilityService) and absolute ring labels ("10:07"/"19:54", likely canvas-drawn and likely NOT readable). Reading the countdown and computing now+X gives the next-event time.
- Direction: read the app's on-screen output on a device the user controls and push the value to the watch. Leaning toward a dedicated always-on device (a cheap old phone, or an Android environment on an always-on machine — verify Play Integrity lets Napper sign in there). The watch stays paired to the carried phone, so a stationary reader must relay the value to it. See DESIGN.md Leg 1.
- Decoupling decision (DESIGN.md): build Legs 2+3 (transport + watch corner) first against a dummy source so the watch feature never blocks on Leg 1.

## [init] 2026-08-01 — Napper parked; pivot to clock sync

- Napper next-event corner parked pending the user reaching out to Napper AB about cooperation. The ToS-circumvention deliberation was scrubbed from the docs and git history earlier (branch history rewritten, reflog expired, gc pruned; no remote, nothing pushed).
- New focus, worked one idea at a time (see TODO.md): (1) sync the GrapheneOS clock app stopwatch+timer with the watch, both running in the background on the watch; (2) watch-configured scheduled brightness + silent mode; (3) wrist-raise shows a locked screen that rejects touch until the button is pressed.
- Repo is now the master for a multi-feature project; upstream trees (InfiniTime already, the Android clock app and possibly Gadgetbridge to come) are git submodules.
- Research started for idea 1: InfiniTime timer/stopwatch internals + background execution model, deskclock internals + fork/extend surface, Gadgetbridge bidirectional transport.

## [flash] 2026-07-19 — sealed-watch OTA is low-risk

- DFU writes to a secondary SPI-flash slot; bootloader swaps + runs unvalidated; reset-before-Validate rolls back; ~7 s watchdog catches a hung image; physical button gives blue=rollback / red=recovery firmware. OTA never touches the bootloader. Rule: never tap Validate until BLE reconnects and a reboot survives with the new corner working.
