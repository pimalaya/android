# Release orchestration: phone mirror and calendar

The handoff for the session that builds what was planned on 2026-10-09. That session supervises: it hands each change to agents, reviews and corrects what comes back, and holds the gates below. The plans themselves are the cairn changes; this file is the order, the decisions and the rules around them.

## State at handoff

- **Committed, awaiting the user's device test:** `account-settings-page` (5e72ce6) and `background-check` (e536343), not pushed.
- **Active, device test owed:** `standard-onboarding-one-password`, `advanced-onboarding-pages`, and the two above.
- **Planned, not started:** the five changes below.

## Order

Each step ends with a commit on master (no push until the end, no `Co-Authored-By` or AI trailer):

1. **`phone-contacts-mirror`**: explicit option, event-driven triggers, detection of the same address under another account type.
2. **`calendar-zones-and-series`**: the agenda at the reader's time, zones kept and written, the recurrence set, this occurrence / this and following / all. Closes two blockers of docs/production-review.md.
3. **ical-rs**, in ~/code/pimalaya/ical under its own cairn: `a-conflict-names-its-sides` and `a-removed-component-comes-back-for-an-edit`. Consumed here through a path dependency (`ical-rs = { path = "../../ical" }` or a `[patch.crates-io]`), never a version bump; the user publishes.
4. **`calendar-conflict-form`**: calendar conflicts triaged by `IcalMerge`, the rest settled in a form like contacts'.
5. **Calendar mapping research**, then a **stop**: agents study DAVx5 (synctools, ical4android), AOSP `CalendarProvider` and Etar, ICSx⁵ and Fossify Calendar against docs/calendar-mapping.md; the supervisor revises the contract and **asks the user to agree it** before any mirror code.
6. **`phone-calendar-mirror`**: Rust projection and patch, `CalendarMapping`, `CalendarRemote`, accounts and the calendar sync adapter, triggers, the switch.
7. **Push** at the end.

## Decisions (2026-10-09)

| Question | Answer |
|---|---|
| Onboarding switch "Also in the phone's Contacts / Calendar app" | On by default, both setups |
| Address already on the phone through Google, DAVx5 | Not mirrored, said on the result page and in settings, settings can force it; only if the check stays one cheap provider query |
| Single occurrences | The full feature: this occurrence, this and following (split series), all |
| Calendar conflicts | A form like contacts', never dropping a side |
| Android accounts for calendars | One per Pimalaya account, named by the address, so calendar apps group per address; which accounts and calendars reach the phone is chosen in Pimalaya (switch in setup, per calendar in settings). Contacts keep one account per book |
| ical-rs fixes | Implemented in ical-rs, path dependency until the user releases |
| Mapping contract | Researched by agents against existing apps, then agreed with the user |
| Zones | Platform tzdata through `java.time`; no time-zone database in the native library (`tzdb` feature off); non-IANA `TZID`s through ical-rs `IcalTz`, Windows names through the CLDR table |

## How to run each change

1. **Read** the change's proposal, tasks and delta, and the spec files it folds into. Settle anything ambiguous with the user before agents start.
2. **Split** into agent tasks along seams that do not touch the same files: Rust (`rust/src/`) against Java, the pure mapping (JVM-testable) against the device adapter, the UI against the engine. Each agent gets the change's files, the files it owns, the rules below, and a definition of done (its part builds and its tests pass).
3. **Review** every result before merging it into the next: read the diff, check it against the proposal and the repository's conventions (comment density, naming, no helper before two call sites, no em dashes), send it back with precise corrections when it misses.
4. **Gate**: `nix develop --command gradle -p android :app:assembleDebug :app:testDebugUnitTest --console=plain` green; for Rust work also `nix develop --command cargo test`, `cargo clippy` and `cargo fmt` in `rust/`; `cairn/verify.sh` conformant.
5. **Install** the APK (`nix develop --command adb install -r android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`), tick the change's tasks, commit.
6. **Leave the change active**: folding the delta into the spec, the log entry, the archive and the CHANGELOG wait for the user's device test.

## Rules every agent gets

- Gradle and cargo only through `nix develop --command`, from the repository root.
- The build is the bar. Never drive the UI through adb to verify; installing the APK is fine.
- Never transcribe pimdir SQL or a column list into Java: the schema is io-pimdir's, over JNI.
- Never print a token or a secret. Live tests on the Google, Microsoft and Fastmail test accounts only; Posteo is personal and never touched.
- No mutating git from agents: the supervisor commits.
- No em dashes in prose. Concise code and docs; the cairn files may be verbose.
- Never adjust production code to fit a test.
- Never bump a crate version.
