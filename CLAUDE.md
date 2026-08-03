# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Personal hacking playground for a PineTime smartwatch running InfiniTime, paired to an Android phone (GrapheneOS) running Gadgetbridge. The repo hosts several features, worked one at a time; `TODO.md` tracks current state and what is parked. Undertakings and their status:

1. Clock sync (parked, 2026-08-03 user decision) — the clock app's stopwatch and timer sync with the watch, both directions. Implemented (Clock T fork + the InfiniTime ClockSync BLE service) but field-flaky; the user judged it not worth further debugging for now. Code stays in the tree.
2. Scheduled brightness + silent mode (planned) — on a watch-configured schedule, switch brightness and toggle silent mode (e.g. dim + silent at night).
3. Wrist-raise lock (implemented, pending field verification) — waking via raise-wrist shows the screen but rejects touch until the physical button unlocks; shield indicator on the Casio G7710 face.
4. Next-event corner (parked) — show a "time of next event" (Napper next sleep/wake) in the G7710 bottom-left corner. Parked pending outreach to Napper AB.
5. Extended-Latin åäö in notifications (implemented, pending field verification) — Latin-1 Supplement glyphs in the UI font + UTF-8-safe truncation.
6. In-call DTMF key tones (implemented, in field verification) — an InCall watch app (hang up, numberpad, configurable intercom key) so the intercom door can be opened from the watch; spans all four forks.
7. Disconnect warning buzz (implemented, pending field verification) — two short buzzes when the BLE connection drops, no screen wake.

Per-feature design docs live in `doc/` (`DESIGN-clock-sync.md`, `DESIGN-lock-screen.md`, `DESIGN-intercom-keytones.md`, `DESIGN.md` for the next-event corner); `doc/clock-sync-setup.md` is the runbook for installing/configuring the whole stack on the phone and watch. Upstream projects are forked with this repo as the master, as git submodules (see Repo layout); the deployed Android apps follow the "T" naming scheme (Clock T, Phone T, Gadgetbridge T) and install alongside their originals.

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
- Submodules (all live): `InfiniTime/` (github.com/Tubbles/InfiniTime, branch `clock-sync`, base 1.16.1 — all firmware work); `deskclock/` (pristine AOSP DeskClock checkout, branch `17`, read-only API reference); `phone/deskclock-app` (Clock T: BlackyHawky/Clock fork with the ClockSync bridge, github.com/Tubbles/Clock branch `clocksync`); `phone/dialer-app` (Phone T: Fossify Phone fork with key-tone/call-state support, github.com/Tubbles/Phone); `phone/gadgetbridge-app` (Gadgetbridge T: github.com/Tubbles/Gadgetbridge branch `callstate`, base 0.92.2).
- `phone/clocksync/` — the canonical, plain-JVM-testable copy of the ClockSync bridge sources; the Clock T fork carries an adapted copy, and CI guards the frame codec against drift.

## Building and CI

- Firmware (InfiniTime fork) is built with the official image `docker.io/infinitime/infinitime-build` (toolchain baked in). Local build with rootless podman: `podman run --rm --userns=keep-id --security-opt label=disable -e SOURCES_DIR=/repo/InfiniTime -e BUILD_DIR=/repo/InfiniTime/build -e DISABLE_POSTBUILD=true -v "$(pwd):/repo" docker.io/infinitime/infinitime-build /opt/build.sh pinetime-mcuboot-app`. The OTA DFU zip lands at `InfiniTime/build/src/*dfu*.zip`. Notes: rootless podman needs `--userns=keep-id`; mount the whole repo (not just `InfiniTime`) so git version detection resolves through the submodule; the DFU is a post-build step of the `pinetime-mcuboot-app` target, so `DISABLE_POSTBUILD=true` still produces it and avoids the recovery-collection step that otherwise fails. For a fast compile check use target `pinetime-app`.
- Phone side uses the Nix flake (`nix develop .#android` for the Android SDK + JDK 17 + Gradle; `.#jvm` for just a JDK). The ClockSync frame codec is tested standalone with plain `javac`/`java` (see `phone/clocksync/test/`).
- APKs are built by CI: `phone-app.yml` (artifact `clocksync-clock-apk`), `phone-dialer.yml` (artifact `keytones-dialer-apk`; builds `assembleFossDebug`, the foss flavor of three), and `gadgetbridge.yml` (artifact `gadgetbridge-t-apk`; builds `:app:assembleMainlineDebug` with the repo's own Gradle 9.5.1 wrapper and JDK 21 on the runner's writable SDK — GB needs the minor platform 36.1, which AGP auto-installs, so the Nix SDK cannot serve it). The Android builds are memory-hungry (Gradle heaps up to 4 GiB), so on modest machines push and let CI build rather than building locally.
- CI: `.github/workflows/firmware.yml` (artifact `pinetime-mcuboot-app-dfu` via the build image) and `.github/workflows/phone-codec.yml` (the frame codec test), all live and green on `github.com/Tubbles/pinetime-hacks`. All three APK workflows sign with the committed `.github/debug.keystore`, so CI artifacts update each other in place on the phone.

## Git workflow

- Commit directly to `master`; no feature branches until the project grows to need them.
- Small, focused commits; each addresses a single concern. Never force-push.
- Remotes are live (GitHub, see Repo layout). Committing and pushing — the submodule first, then the master-repo bump — is the standing close-out step for every change; CI builds the artifacts the user installs, so unpushed work is undeployable.
