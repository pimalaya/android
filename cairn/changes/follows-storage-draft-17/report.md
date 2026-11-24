---
cairn: change
id: follows-storage-draft-17
part: report
---

# Report

The app follows storage-draft-17, sync-draft-15 and search-draft-17, shape A (the Java store ported), on io-pimdir 17d4ea8 (0.20.0 release candidate, requirement `0.20.0`, git patch kept). Run on 2026-11-23 to the timebox; journal.md holds the timed record.

## Final gate (19:12)

- `cargo fmt --check` and clippy `-D warnings` clean; Rust 746 passed, 0 failed, 1 ignored.
- JVM 1,075 run, 0 failed, 3 skipped (the measures).
- `:app:assembleDebug` and `:app:assembleRelease` (R8) built.
- Sync vectors: 213 cases, 161 pass, none fails, 52 unbound (the 13 new rekey cases 201 to 213 among them; the app runs no rekey). Search vectors: 298 run, 7 unbound.
- No rusqlite in the normal tree; `cargo deny` clean; cairn/verify.sh conformant.

## Silent loss

None found. The class that could have lost data silently was the app's statements reading or writing body pointers as hashes after `objects.id`; it is fixed and guarded by `every_pointer_read_answers_a_hash`, which fails on the previous text.

## Landed

- Pin and flake by git rev; `indexer` alone in the library.
- Store: flags as `marks` and `keywords` computed by the bridge, one `summary` and `summary_value`, every pointer joined by id, the 16 moved forks re-derived, new forks for UPSERT_SUMMARY, ADD_PROPERTY and ROLL_COVERAGE.
- Index: draft-17 tables in FTS4, served statements limited to the 50 the bridge binds.
- Search: `mid:`, instants, `label:`, `property:`, `reserved-form`; sorts `NewestFirst` and `Title`; a search paged by the last hit's place, never an offset (G5).
- Sync: `LandPlacement`, with `COPYUID` and Graph's move id stated as `assigned` (F1, F3); `server_object_id` written, loaded and looked up per source, stated by Gmail, JMAP and Graph (F4).
- Refusal screen worded by order: a later store asks for an update and is never offered for deletion (C2).
- Bugs G1, G2, G3, G5, G6, G7, G8 fixed, each test failing on the previous code; H5 ported (a load naming no link reads no placement).
- A test flake explained and fixed: `the_agenda_reads_occurrences_with_their_rows` failed with `Owned(<tmp>)` at rust/src/search/tests.rs:1135 because a child process forked by another test keeps the store's owner lock until it execs, so a store closed and reopened can be found owned; the five search tests that reopen a store now hold one open throughout (failed at run 7 of 15 under load before, 25 of 25 after).

## Measures (CPU 15, measure lock, other lanes building: indicative)

- First-sync page of 500 messages: median 249 ms against 425 at H0 (0.59x, threshold 1.10x); unstamped 148 against 304.
- Agenda index: 3 months 38 to 46 ms against 48 to 69; first build 1.2 to 1.8 s against 1.9 to 2.6 s.
- H3: the query-plan asserts hold on SQLite 3.18.2.

## Parked (reasons in journal.md)

- F2, settled collections (needs a per-provider "no change since this checkpoint" probe), and H2 with it.
- F4's IMAP half: io-imap reads no `EMAILID`.
- G4: needs `capped` carried on MailPage.
- H4: no search baseline was taken at H0.
- H6: the owner's phone measure.
- K1: done 2026-11-24 (tasks.md).

## Decisions beyond the plan's text

- ADD_PROPERTY's fork inserts where the value is absent, not `INSERT OR IGNORE`, which would swallow a CHECK.
- ROLL_COVERAGE gets a fork.
- `held_message_object` matches the account with `IS`, as the canonical text does.
- The lookup yield names the collection the run last loaded.
- Only a later store gets the "update" screen.
- Two Java signatures changed: `PimdirIndexDb.search` takes a cursor token, and `MailClient.relocate` returns the assigned handle (the `assembleRelease` smoke passed).

## The owner's checks (never driven by adb)

O1 to O5 of tasks.md. O1: installing over the current app shows the reinstall screen with `store-version (storage-draft-15, earlier than this app's storage-draft-17)`. O2: a fresh pull-only Fastmail sync (its duration, inbox, a contact, the agenda; search `is:unread`, `from:`, a body word, `mid:`). O3 waits for F2. O4: a move on the Google or Microsoft test account shows once in its target. O5 waits for K1.
