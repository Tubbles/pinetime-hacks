# Log

Grep-able log of learnings, decisions, dead ends, and verified facts that the commits and code do not capture. Newest entries at the bottom of each tag section is fine; keep entries tagged for grep: `[init]`, `[infinitime]`, `[napper]`, `[gadgetbridge]`, `[ble]`, `[flash]`, `[build]`.

## [init] 2026-07-19 — repo created, research phase started

- Repo scaffolded (CLAUDE.md, doc/, TODO.md, SUGGESTIONS.md), conventions adapted from the zephyr-pump sibling repo.
- Watch is sealed — no SWD. All flashing must go through OTA/DFU via Gadgetbridge. Validation/rollback semantics must be understood and written down here before the first flash of self-built firmware.
- Research running on three tracks: InfiniTime internals (Casio G7710 face, BLE services, build/flash), Napper app data-extraction surface, Gadgetbridge/phone-side delivery path.
