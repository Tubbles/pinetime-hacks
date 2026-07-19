# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Personal hacking playground for a PineTime smartwatch running InfiniTime, paired to an Android phone running Gadgetbridge. The first undertaking: show the "time of next event" in the bottom-left corner of the Casio G7710 watch face (where the heart-rate BPM indicator lives) whenever heart rate is not in use. The initial event source is the Napper baby-sleep app (com.napper) on the phone — its predicted next sleep/wake time — delivered to the watch automatically, with no manual input. Other sources (next alarm, calendar) are deliberately out of scope for now.

Hardware context that shapes everything: the watch is **sealed** (no SWD access), so flashing is OTA/DFU-only via Gadgetbridge. Firmware safety (bootloader intact, validation/rollback flow understood) is a hard requirement before any flash. Record findings about this in `doc/LOG.md`.

## Documentation and process

Project docs live in `doc/` (design documents, research notes, LOG.md); `TODO.md` and `SUGGESTIONS.md` stay in the repo root. `doc/LOG.md` is a grep-able log of learnings and decisions (BLE gotchas, flashing findings, dead ends) that the commits and code do not capture; skim it before debugging hardware or protocols.

You have standing permission to create new documents and set up new processes whenever you notice one is missing, without asking first. When you learn something worth keeping, write it down then and there: design notes and references under `doc/`, outstanding work in `TODO.md`, your own ideas in `SUGGESTIONS.md`. Prefer extending an existing doc over starting a parallel one, and add a pointer from CLAUDE.md or the README when a newcomer should be able to find the new doc.

Close out every work item by documenting it before you commit. Put the learnings, gotchas, dead ends, verified facts, and the reasoning behind decisions in `doc/LOG.md`: the git diff records what changed, the log records what you found out and why. Delete finished items from `TODO.md` once their record is in `doc/LOG.md`, and keep the unfinished ones. The documentation pass is part of the work, not an optional extra.

## Repo layout

- `CLAUDE.md` — this file.
- `README.md` — short project overview.
- `doc/` — design documents, research reports, `LOG.md`.
- `TODO.md` — outstanding work. The `## User written inbox` section holds the user's own spit-ball items verbatim; keep it intact and commit user additions in their own commit.
- `SUGGESTIONS.md` — candidate To Dos noticed during work: optional ideas, not committed work. An item only becomes a To Do when deliberately promoted into `TODO.md`.
- `tmp/` — scratch and intermediate files, gitignored.
- `work/` — brainstorming and throwaway specs, gitignored.
- Submodules (planned): upstream source trees we hack on (InfiniTime, possibly Gadgetbridge). Not added yet; see `TODO.md`.

## Git workflow

- Commit directly to `master`; no feature branches until the project grows to need them.
- Small, focused commits; each addresses a single concern. Never force-push.
- No remote is configured yet. Once one exists, committing and pushing become the standing close-out step for every change.
