# To Do

The repo is a personal PineTime/InfiniTime hacking playground; the feature list and statuses live in `README.md`, finished work is recorded in `doc/LOG.md` and git history. This file holds only what is outstanding or parked, and the reasoning behind those states.

## Reopened: DFU reliability (user report 2026-08-11), root cause found 2026-09-17

Root cause (LOG.md 2026-09-17, from the on-watch trace of a failed flash): the DFU control point's CCCD write succeeds, but persisting it overflows NimBLE's 8-entry CCCD store, the overflow handler finds no other peer to evict and returns `BLE_HS_ENOMEM` (6), and the ATT layer sends that number as error 6, REQUEST NOT SUPPORTED, which aborts the DFU. Gadgetbridge T's eight subscriptions (six stock plus this fork's ClockSync and KeyTones) fill the store exactly.

Fixed in firmware on 2026-09-17 and on the watch since that day, in InfiniTime `e3aefaf3` (its event log shows that build booting, then a phone flash that went through although the DFU subscription could not be saved): InfiniTime `50b477ef` (a failed persist no longer fails the CCCD write; trace record type 8), `2ae6b7ed` (the DFU revision characteristic served its own attribute handle), `0591f122` (trace read-out paged by MTU, discovery terminators no longer traced). Open:

1. Confirm phone OTA: the item closes when normal OTA from the phone has succeeded a few times on the fixed build, with the watch awake (point 3). So far: the phone flash of 2026-09-17, and a laptop flash of InfiniTime `2f6252b3` on 2026-10-27 (runbook section 1), which does not involve the bond store.
2. Firmware, optional hardening: raise `BLE_STORE_MAX_CCCDS` (syscfg.h, 8) once `PersistBond` in `NimbleController.cpp` no longer keeps `MAX_CCCDS` copies of the 72-byte `ble_store_value` union on the host task stack (use `ble_store_value_cccd`), and consider never persisting the DFU control point CCCD at all.
3. Firmware: an event log write from the Bluetooth task hangs a sleeping watch, and the watchdog resets it (LOG.md 2026-10-27). The DFU start line, the DFU image line, the wrong-state line and the bond store failure line all reach the SPI flash before anything wakes the watch, while the system task keeps the flash and the SPI bus powered down during sleep. Every phone flash writes the bond store line. Candidate fixes: wake the watch before the write the way `FSService::FSCommandHandler` does, or hand the line to the system task to write. Until then the runbook says to wake the watch before a phone flash.

Field evidence on file: `tmp/trace.bin` (pulled 2026-09-17 with `tools/pull_watch_file.py`), decoded in the log entry.

## Parked: 1. Clock sync (user decision 2026-08-03)

Field verdict after the fourth on-device round: "it hardly works at all ... i dont think its useful enough of a feature for this level of issues." The code stays in the tree (firmware service, Clock T bridge, CI) but no further debugging until the user un-parks it. Next diagnostic step if resumed: GB T file log of one phone-side play press (see the runbook troubleshooting section), since the epoch fix (InfiniTime `dfbd9676`) has still never been verified as delivered on-device. Known open v1 limitations (accepted): state characteristic is NOTIFY-only (no READ); timer expiry is not notified (phone derives it); stopwatch laps not synced; a watch-initiated stopwatch run cannot seed the phone's accumulated time; InfiniTime's timer has no pause, so phone-side pause maps to stopped.

## Planned: 2. Scheduled brightness + silent mode

On a schedule configured entirely on the watch (no hard-coded times), switch screen brightness and toggle silent mode. Example: at 20:00 go to lowest brightness + silent; at 07:00 go to middle brightness + full noise. Configuration lives on the watch. Not designed yet.

## Parked: next-event watch face corner (Napper)

Design: `doc/DESIGN.md`, research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Parked pending the user reaching out to Napper AB about cooperation / their thoughts. Standing finding: Napper offers no API, web client, widget, or notification carrying the time, so the only avenue was reading the app's on-screen output (ranked approaches in `doc/DESIGN.md` Leg 1). Nothing to do here until the outreach resolves.

## User written inbox
