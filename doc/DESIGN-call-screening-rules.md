# Design: regex call-screening rules in Phone T

Status: implemented 2026-10-01; current status in `README.md`. User request: "a list of blacklist and whitelist regex rules that automatically blocks calls based on their canonicalized phone number (eg +46123456789 for a swedish number) ... a stack of rules that run from top to bottom, each rule being either a blacklisting or a whitelisting, and containing a regex (eg blacklist '.*' followed by a whitelist '+46.*' would block all calls from abroad)".

## Semantics

- A rule is `(action, regex)`, action BLOCK or ALLOW. The list is ordered.
- The incoming number is canonicalized to E.164 (`+46123456789`) with `PhoneNumberUtils.formatNumberToE164` and the SIM/network/locale region, the same canonicalization `Config.getCustomSIM` already uses; if that fails (a number that cannot be parsed) the raw digits are matched instead.
- Every rule is tried in order, `Regex.matches` against the whole canonical number. **The last matching rule decides.** No rule matching means allow. This is the reading under which the user's own example works: `BLOCK .*` then `ALLOW \+46.*` blocks every foreign number and lets Swedish ones through; a later `BLOCK \+4610.*` would carve a block back out of that. First-match-wins would have made the example's second rule dead.
- Hidden numbers (no caller id) cannot be matched by a regex, so the rules screen carries a separate switch, "Block hidden numbers" (user request, same day), stored in commons' existing `blockHiddenNumbers` pref. Fossify's own copy of that switch sits in its Compose blocked-numbers screen, which Phone T gates behind the "Thank you" paywall dialog, so it was unreachable; the rules screen exposes the same pref and a hidden-number block posts the same "Call blocked" notification as a rule. The rules run after Fossify's own blocked-number list and before its "block unknown numbers" check, so an explicitly blocked number stays blocked whatever the rules say.
- A regex that fails to compile is skipped (the editor refuses to save one, so this only covers a corrupted store).

## Where it lives

- `helpers/CallScreeningRules.kt`: `data class ScreeningRule(val block: Boolean, val pattern: String)`, JSON in a pref (`call_screening_rules`, Gson like the speed-dial list), `fun evaluate(rules, canonicalNumber): Boolean?` (true = block, false = allow, null = no rule matched) as a pure function, and `fun canonicalize(context, number): String`.
- `services/SimpleCallScreeningService.kt`: one new `when` branch between the blocked-list check and the unknown-number check.
- `activities/ManageCallScreeningRulesActivity.kt` + `adapters/CallScreeningRulesAdapter.kt`, modelled on the speed-dial manager: a drag-sortable list (block rules in red, allow rules in green, pattern as the text), a FAB that opens an add dialog, tap to edit, multi-select delete. The dialog (`dialogs/CallScreeningRuleDialog.kt`): a BLOCK/ALLOW radio pair, the regex field, and a "test number" field that shows live whether the typed number would match. Save is refused for an invalid regex (toast with the error).
- Settings -> "Call screening rules" row, next to "Manage blocked numbers". The call-screening role is requested when the list is non-empty, the same `setDefaultCallerIdApp()` flow MainActivity uses for the block-unknown switches; without the role Android never asks the app, so the rules would silently do nothing.
- **Blocked calls go into the call history** (user request, same day: "put it in call history ... with some sort of filter option"). The screening response keeps skip-notification (that only suppresses the system's missed-call entry) but clears skip-call-log, so Android itself writes the entry with its native `Calls.BLOCKED_TYPE` (API 24, below this app's minimum of 26), including hidden-number blocks, which the log stores as number presentation restricted. Nothing is persisted by the app. The recents list draws a blocked entry with a distinct icon and colour, and the Recents tab's overflow menu gains a three-way choice: show all calls, hide blocked calls, only blocked calls, stored in a pref and applied as a `Calls.TYPE` selection on the call-log query. Fossify's own blocked-number list keeps skip-call-log, so its blocks stay invisible as before. In addition every block, by rule or by the hidden-number switch, posts a "Call blocked" notification with the canonical number (or "hidden number") and the rule's pattern, one per call; tapping it opens the Recents tab.

## Decisions

- **Last match wins** (above). Documented in the rules screen's hint text so the mental model is on screen.
- **Match the whole number** (`matches`, not `containsMatchIn`): `+46.*` means "starts with +46"; a user who wants a substring writes `.*`. Anchoring is implicit and consistent.
- **Canonical E.164 as the match subject**: the user's spec. The raw-digits fallback only exists because the screening service must answer for every call.
- **No contact lookup in the rules**: that is Fossify's "block unknown numbers" switch and stays separate.
- **Role request on save, not on boot**: the role dialog is a user-facing prompt and belongs to the moment the user enables the feature.
