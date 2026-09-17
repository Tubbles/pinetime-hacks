# Design: error visibility on the watch (event log, notifications, log screen)

Status: implemented 2026-09-17; current status in `README.md`. Origin: the DFU root-cause hunt (`doc/log/2026-09-17.md`), which took two months of phone-side guessing and ended in ten minutes once the watch's own view was readable. The user's ask: "any error messages on the watch? a notification with the error text? a scrollable error log app?"

## Goal

When something rare and bad happens on the watch (a flash aborts, a subscription cannot be saved, the watch reboots from a watchdog), the user learns it on the wrist with the cause in words, and the record survives until someone reads it, with no laptop in the loop.

## Pieces

1. **Event log** (`components/eventlog/EventLog.{h,cpp}`): `EventLog::Log(text)` appends one line, `YYYY-MM-DD HH:MM:SS text`, to `/events.log` in littlefs; `EventLog::LogAndNotify(title, text)` also raises a watch notification (category SimpleAlert, message `title\0text`, the same call the file-transfer service uses for its "access disabled" alert). When `/events.log` passes 4 KiB it is renamed to `/events.old` and a fresh file starts, so the flash cost is bounded at 8 KiB and the history keeps the previous generation. Timestamps come from `DateTime`; before the phone has set the clock they show the default epoch, which is still ordered.
2. **Event sites**, chosen for rarity and diagnostic value:
   - boot: `boot <git hash> reset=<reason>` (log only). A watchdog or lockup reset is the one event nobody sees otherwise.
   - DFU: start with the image size (log); image validated and activate-and-reset (log); timeout with the state and the percentage received, which is also how a mid-transfer disconnect ends (log + notify); bad CRC (log + notify); a control-point request in the wrong state (log + notify).
   - CCCD persist failure, the bug behind every refused flash (log + notify, from the C hook `infinitime_event_cccd_persist_failed` next to the existing trace hook).
3. **Log screen**: Settings -> "Event log", `SettingEventLog`, a `ScreenList` of pages built from the tail of `/events.log`, newest first, word-wrapped; swipe up and down between pages like About. Read once at screen creation into a heap buffer sized for the pages, not into static RAM (`doc/log/2026-08-10.md`: static buffers starve the heap).
4. **Off-watch reading** stays: `tools/pull_watch_file.py <mac> /events.log tmp/events.log` (and `/events.old`) from the laptop. A "download watch log" button in Gadgetbridge T is the natural next step and lives in `SUGGESTIONS.md`.

## Decisions

- **The trace ring stays the fine-grained instrument** (every ATT error, subscribe, GAP event) and the event log is the coarse, human-readable one. They do not duplicate each other: the ring is 64 records in RAM for forensics, the log is a handful of lines per week that a person reads.
- **Writes happen in the calling task**, the BLE host task for DFU and CCCD events and the system task at boot, exactly as the bond persistence already writes from the host task. The littlefs wrapper has no lock, so a write racing the log screen's read (display task) or a laptop pull (host task) is possible in principle; the events are rare and the reads are user-initiated, the same class of exposure upstream already carries between settings saves and resource loads. If it ever bites, the fix is a mutex in `FS`, not in the logger.
- **Notifications only for aborts and failures.** Starts and successes are visible on their own (the DFU screen, the reboot). A notification per boot would be noise.
- **Formatting on the stack**, a 96-byte buffer per call. The host task stack showed 892 B of headroom after the August measurement, and the littlefs append itself is what the bond code already does there.
- **No new font or symbol**: the screen uses the 20 px default font and an existing settings symbol.

## Verification

Flash, then: open Settings -> Event log and see the boot line; start a DFU and cancel it mid-way (or let the phone walk away): a notification "DFU: timeout in Data at 37 %" must appear and the log must carry the line; the CCCD line appears only if the store overflows again, which the 2026-09-17 fix should make rare.
