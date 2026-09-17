# To Do

The repo is a personal PineTime/InfiniTime hacking playground; the feature list and statuses live in `README.md`, finished work is recorded in `doc/LOG.md` and git history. This file holds only what is outstanding or parked, and the reasoning behind those states.

## Reopened: DFU reliability (user report 2026-08-11), root cause found 2026-09-17

Root cause (LOG.md 2026-09-17, from the on-watch trace of a failed flash): the DFU control point's CCCD write succeeds, but persisting it overflows NimBLE's 8-entry CCCD store, the overflow handler finds no other peer to evict and returns `BLE_HS_ENOMEM` (6), and the ATT layer sends that number as error 6, REQUEST NOT SUPPORTED, which aborts the DFU. Gadgetbridge T's eight subscriptions (six stock plus this fork's ClockSync and KeyTones) fill the store exactly. Not yet fixed. Work items, in order:

1. Firmware: in `ble_gatts_clt_cfg_access` (vendored NimBLE, `ble_gatts.c`) a failed persist must not fail the CCCD write; keep the subscription, return 0, and emit a trace record so the condition stays visible.
2. Firmware: raise `BLE_STORE_MAX_CCCDS` (syscfg.h, 8) once `PersistBond` in `NimbleController.cpp` no longer keeps `MAX_CCCDS` copies of the 72-byte `ble_store_value` union on the host task stack (use `ble_store_value_cccd`).
3. Firmware: `DfuService.cpp` passes `&revision` as the revision characteristic's `.val_handle`, so the served revision is the attribute handle; give it its own handle variable. Upstream candidate.
4. Firmware, trace tooling: page the 00080003 read-out by `ble_att_mtu(connHandle) - 2` (the runbook's MTU workaround stays until then), and stop tracing the service-discovery terminators (ATT error 0x0a on opcodes 0x04 and 0x08), which fill the 64-slot ring 30 records per discovery.
5. Flashing the fix: the running firmware refuses the DFU CCCD, so flash through the recovery firmware (runbook section 1, button held on boot until the pine cone is red); it has an empty store.

Field evidence on file: `tmp/trace.bin` (pulled 2026-09-17 with `tools/pull_watch_file.py`), decoded in the log entry.

## Parked: 1. Clock sync (user decision 2026-08-03)

Field verdict after the fourth on-device round: "it hardly works at all ... i dont think its useful enough of a feature for this level of issues." The code stays in the tree (firmware service, Clock T bridge, CI) but no further debugging until the user un-parks it. Next diagnostic step if resumed: GB T file log of one phone-side play press (see the runbook troubleshooting section), since the epoch fix (InfiniTime `dfbd9676`) has still never been verified as delivered on-device. Known open v1 limitations (accepted): state characteristic is NOTIFY-only (no READ); timer expiry is not notified (phone derives it); stopwatch laps not synced; a watch-initiated stopwatch run cannot seed the phone's accumulated time; InfiniTime's timer has no pause, so phone-side pause maps to stopped.

## Planned: 2. Scheduled brightness + silent mode

On a schedule configured entirely on the watch (no hard-coded times), switch screen brightness and toggle silent mode. Example: at 20:00 go to lowest brightness + silent; at 07:00 go to middle brightness + full noise. Configuration lives on the watch. Not designed yet.

## Parked: next-event watch face corner (Napper)

Design: `doc/DESIGN.md`, research: `doc/research-{infinitime,napper,gadgetbridge}.md`.

Parked pending the user reaching out to Napper AB about cooperation / their thoughts. Standing finding: Napper offers no API, web client, widget, or notification carrying the time, so the only avenue was reading the app's on-screen output (ranked approaches in `doc/DESIGN.md` Leg 1). Nothing to do here until the outreach resolves.

## User written inbox
