# Design: YACB T, the regex rule stack inside Yet Another Call Blocker

Status: implemented 2026-10-01; current status in `README.md`. Origin: the Phone T call-screening rules (`doc/DESIGN-call-screening-rules.md`) collided with the one-app-per-role rule of Android call screening: the user runs Yet Another Call Blocker (YACB, gitlab.com/xynngh/YetAnotherCallBlocker, GPL) for its crowd-sourced spam database, which "catches some calls but not all". Decision (user, 2026-10-01): fork YACB, put the rule stack there, switch its pattern engine to regex, keep its notifications; Phone T's copy stays in the tree but is not the active one.

## What changes in YACB

YACB's blacklist already is a list of patterns with a per-item name, call counter, CSV import/export, and a notification on every block. It becomes the rule stack rather than gaining a sibling list:

- **Pattern engine**: regular expressions (`java.util.regex`) over the number in E.164 form (`+46701234567`, from YACB's own `NumberUtils.normalizeNumber`, which is `PhoneNumberUtils.formatNumberToE164` with a stripped-separators fallback), full-match (`Matcher.matches`). Upstream matched SQL LIKE patterns (`%`/`_`, shown as `*`/`#`) against the digits of the raw number in the database query; that query is replaced by an in-memory evaluation of every item, which at a few dozen rules costs nothing.
- **Two new columns on the entity**: `allow` (boolean, default false: a plain blacklist entry blocks) and `position` (integer, the explicit order). greenDAO `schemaVersion` 1 -> 2 with an `ALTER TABLE ... ADD COLUMN` pair in `YacbDbOpenHelper.onUpgrade`, so an existing YACB blacklist survives an in-place update (every old item is a block rule in creation order).
- **Evaluation**: `BlacklistService.getBlacklistItemForNumber` returns the LAST item, in position order, whose regex matches; an `allow` item that wins yields a new `NumberInfo.BlockingReason`-free verdict. In `NumberInfoService.getBlockingReason` the order is: contact -> never block (unchanged); hidden number (unchanged); **rule stack**: an allow match returns null even if the database rating is negative, a block match returns BLACKLISTED; then the database rating as before. So an `Allow \+4610.*` entry whitelists a series the crowd data dislikes, the Försäkringskassan case, and `Block .*` then `Allow \+46.*` blocks abroad. Hidden numbers never reach the stack (YACB's own switch handles them).
- **Legacy patterns**: an item whose pattern still contains `%` or `_` as the old wildcards is converted once at upgrade: `%` -> `.*`, `_` -> `.`, everything else regex-escaped, and the leading `+` becomes `\+`. The CSV import keeps accepting the old human-readable form (`*`/`#`) by the same conversion, and the NoPhoneSpam importer path too.
- **Editor**: a Block/Allow switch, the pattern field as free text (no phone input type), live validation by compiling the regex (`PatternSyntaxException` message shown in the field error), plus a test-number field that shows whether the typed number matches after E.164 normalization. The hint text explains last-match-wins and the full-match rule.
- **List**: ordered by `position`, allow rules in green and block rules in red, with up and down arrows per row (YACB's list uses `recyclerview-selection` for multi-select and `paging` for loading, which fight with drag-sorting; arrows are the cheap, robust choice) that swap positions and persist.
- **Notifications**: unchanged mechanism, YACB already posts one per blocked call (tag per number) with the reason text, and its blocked-calls log already lists every block with the reason. The text for a rule block names the rule (its name, or the pattern when unnamed). A hidden-number block already notifies.
- **Settings**: the "Block blacklisted numbers" switch keeps gating the stack as a whole; its title and summary are reworded to "rules".

## Decisions

- **Rules replace the blacklist instead of sitting beside it**: one list, one evaluation, one notification path, and the upgrade keeps every existing entry as a block rule. Two lists would have needed a precedence story between them.
- **Last match wins, full match**, as in Phone T, for the same reason: the user's example only works that way.
- **Contacts still never block**: a rule `Block .*` must not block a contact (user decision 2026-10-01).
- **Allow beats the database rating**: that is the whole point of a whitelist in a database-driven blocker.
- **In-memory evaluation**: the LIKE query ran in SQLite. Regex cannot, and the list is small. The rules are loaded once per call screening, which already happens for the LIKE path.
- **Arrows, not drag**: see above.
- **CI like Gadgetbridge T**: no Nix shell. YACB pins AGP 7.0.3 and compile SDK 30, which the Nix SDK (platforms 34 to 36) does not carry; the GitHub runner's SDK plus the repo's Gradle 7.2 wrapper and JDK 17 do. Debug signing and the versionCode come from the same two env variables as the other apps.

## Verification

Install YACB T beside YACB, let it download the database, add `Block .*` and `Allow \+46.*`, make it the caller ID app, call from a foreign and a Swedish number: the foreign one is blocked with a notification naming the rule and appears in YACB T's blocked-calls log, the Swedish one rings. Then disable the original YACB.
