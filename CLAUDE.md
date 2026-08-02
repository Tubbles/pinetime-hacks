# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Personal hacking playground for a PineTime smartwatch running InfiniTime, paired to an Android phone (GrapheneOS) running Gadgetbridge. The repo hosts several planned features, worked one at a time; `TODO.md` tracks which is active and what is parked. Current and planned undertakings:

1. Clock sync (active) — sync the GrapheneOS clock app's (`com.android.deskclock`) stopwatch and timer with the watch, both directions, with both running in the background on the watch.
2. Scheduled brightness + silent mode — on a watch-configured schedule, switch brightness and toggle silent mode (e.g. dim + silent at night).
3. Wrist-raise lock — waking via raise-wrist shows the screen but rejects touch until the physical button is pressed; likely a lock indicator on the Casio G7710 face.
4. Next-event corner (parked) — show a "time of next event" (Napper next sleep/wake) in the G7710 bottom-left corner. Parked pending outreach to Napper AB.

Per-feature design docs live in `doc/` (e.g. `doc/DESIGN.md` for the next-event corner). The user is open to forking and extending upstream projects (InfiniTime, the Android clock app, possibly Gadgetbridge) with this repo as the master, using git submodules for the upstream trees.

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

## Building and CI

- Firmware (InfiniTime fork) is built with the official image `docker.io/infinitime/infinitime-build` (toolchain baked in). Local build with rootless podman: `podman run --rm --userns=keep-id --security-opt label=disable -e SOURCES_DIR=/repo/InfiniTime -e BUILD_DIR=/repo/InfiniTime/build -e DISABLE_POSTBUILD=true -v "$(pwd):/repo" docker.io/infinitime/infinitime-build /opt/build.sh pinetime-mcuboot-app`. The OTA DFU zip lands at `InfiniTime/build/src/*dfu*.zip`. Notes: rootless podman needs `--userns=keep-id`; mount the whole repo (not just `InfiniTime`) so git version detection resolves through the submodule; the DFU is a post-build step of the `pinetime-mcuboot-app` target, so `DISABLE_POSTBUILD=true` still produces it and avoids the recovery-collection step that otherwise fails. For a fast compile check use target `pinetime-app`.
- Phone side uses the Nix flake (`nix develop .#android` for the Android SDK + JDK 17 + Gradle; `.#jvm` for just a JDK). The ClockSync frame codec is tested standalone with plain `javac`/`java` (see `phone/clocksync/test/`).
- The dialer fork (`phone/dialer-app`, Fossify Phone based, Kotlin) builds with `gradle assembleFossDebug` (three flavors exist; foss is the one used). Do NOT build it locally on this machine: its dex step OOMs even solo (the machine killed user services twice; see `doc/LOG.md` [build] 2026-08-02 key-tones entry). APKs come from CI (`phone-dialer.yml`, artifact `keytones-dialer-apk`).
- CI: `.github/workflows/firmware.yml` (DFU artifact via the build image) and `.github/workflows/phone-codec.yml` (the frame codec test). They run once the repo and the InfiniTime fork are pushed to GitHub (see `TODO.md`).

## Git workflow

- Commit directly to `master`; no feature branches until the project grows to need them.
- Small, focused commits; each addresses a single concern. Never force-push.
- No remote is configured yet. Once one exists, committing and pushing become the standing close-out step for every change.
