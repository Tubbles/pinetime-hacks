# Suggestions (not yet To Dos)

Everything below was noticed during work and written down so it isn't lost. These are optional ideas and observations — candidate To Dos, not committed work. Nothing here is planned or promised; an item only becomes an actual To Do when deliberately promoted into `TODO.md`.

- Generalize the "time of next event" corner to other event sources once the Napper path works: next phone alarm, Google Calendar (via phone connection). Mentioned by the user at project start as explicitly out of scope for now.
- True padlock glyph for the wrist-raise lock indicator: v1 reuses `Symbols::shieldAlt` (U+F3ED, already in `jetbrains_mono_bold_20`); a real padlock means adding U+F023 to the FontAwesome range in `InfiniTime/src/displayapp/fonts/fonts.json` and regenerating with `fonts/generate.py`. Optional polish, deferred to keep v1 font-neutral.
- Watch timer could adopt the remaining duration of a phone-side paused timer: today a paused phone timer arrives as ClockSync "stopped" with `value_ms` = remaining, and the firmware only calls `StopTimer()`, discarding `value_ms`. Presetting the Timer screen's counters from it would make resume-on-watch seamless. Noticed during the 2026-08-02 review; deferred as beyond the review's contract.
