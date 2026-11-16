---
cairn: log
change: the-store-follows-pimdir-draft-13
date: 2026-11-16
---

# The store follows pimdir draft-13

The app moves from pimdir draft-11 to storage-draft-13, sync-draft-13 and search-draft-13 (pimdir 1c6442e) on io-pimdir 0.14.1, and onto the family releases of 2026-11-15 and 2026-11-16, every one from crates.io: no `[patch]`, git or path source is left in rust/Cargo.toml or rust/Cargo.lock.

## The manifest

rust/Cargo.toml asks io-pimdir 0.14.1 (the dev-dependency too), io-http 0.8, io-oauth 0.7, io-imap 0.11.2, io-smtp 0.9, io-jmap 0.10, io-gmail 0.9, io-gcal 0.5.1, io-gpeople 0.8.1, io-msgraph 0.9.2, io-webdav 0.11, mime-rs 0.2, io-gtasks 0.2 and io-pim-discovery 0.14. ical-rs 0.7 and vcard-rs 0.6 were their latest already. `cargo update` named those fourteen alone and moved nothing else. pimalaya-stream 0.4 is in no tree of the app's: the bridge turns off the client features that pull it. io-pimdir 0.14.1 and io-pim-discovery 0.14 differ from 0.14.0 and 0.13 only by their `cli` feature, which the app does not take; the spec of io-pimdir's v0.14.1 tag is byte for byte v0.14.0's.

## The draft

The store is created, checked and stamped at draft-13 by the crate's names (`sql::FORMAT_VERSION`, `SYNC_VERSION`), so a store an earlier build wrote, a draft-11 one among them, is refused (`store-version`, or `sync-version` for its sync state) and the app opens on its reinstall screen, as at the draft-11 move; an index of an earlier draft is rebuilt. The fork of `init_index_meta` was read again and names the new digest (fbd4eacf9893aaf0), only the draft's name having moved. No other canonical text the app forks moved: `list_unstamped_bindings`, `load_item_capabilities`, `prune_receipts` and `record_receipt` changed and run as io-pimdir writes them. The conformance statement reads `storage-draft-13 owner (own statement text), sync-draft-13 engine`.

`load_item_capabilities` gained `held` (the pending-create withdrawal, STORAGE §15.6): the app runs no queue action through the capability gate (its queue holds submissions alone), so nothing reads it. A receipt now names the item its row acted on; the app's receipts are a submission's, which names none, so `load_receipt_refusals` has nothing to read here.

## Refusals

- **The bridge** (rust/src/error.rs): `BridgeError` gains `refused`, serialized only when true. `coroutine_error` sets it by `refuses`: Graph, Gmail or a JMAP request failing with a status naming the request (`names_the_request`: 400 to 499 but 401, 407, 408, 409, 412, 423, 425 and 429, as neverest's), which names no quota nor rate limit. rust/src/mail/jmap.rs sets it for an `Email/set` refusal of a type naming the change (`refuses_as_it_stands`), rust/src/mail/imap.rs for a `NO` naming `CANNOT`, `PARSE`, `TOOBIG` or `NOPERM` on a write (`write`, over the `WriteFailure` of the five write coroutines). A JMAP `Email/import` refused `forbidden` or `tooLarge` parks a submission for good, `overQuota` and `rateLimit` leave it queued.
- **Java**: `PimalayaException.refused`, read by `Replies.object`. `MailEngine` takes a 404 as a message gone first, as before (`settled`), then answers a refused failure with `PimdirEngine.refusedResult` (`accepted` false, `refused` the server's text) for a marker, a delete, a move, a copy and an append; any other failure throws on as before. A move so refused returns before its target's create is withdrawn: the create stays pending behind the held removal.
- **The engine's wire** (rust/src/offline.rs, wire.rs): a push result's `refused` crosses as `PimdirPushOutcome::Refused { reason }`; a load's `refused` as `PimdirLoaded::refused`; `RefusePlacement` (its reason scrubbed by io-pimdir's `security::scrub` before it crosses) and `ClearRefusal` cross as `refusePlacement` and `clearRefusal`. `PimdirStorage` records them by the canonical `refuse_binding` and `clear_refusal` after the batch's upserts and stamps, as io-pimdir's own owner does, and loads the source's refusals by `list_refusals` on every selection.
- **The report**: `SyncReportJson` and Java's `SyncReport` gain `refusals` (each handle once, its reason scrubbed) and `unlisted`; `Stager.synced` logs each refusal as a warning. No screen shows one.

## What a release changed that would drop data

- **io-imap 0.11** decodes a FETCH response item by item and hands the line it read in part in `partial`, where 0.9 skipped it whole. `taken` counts a partial line as unread, and `taken_fetch` leaves the message it read in part out of a FETCH answer: a header fetch would otherwise list a message whose `FLAGS` did not decode as one with no marker, and a size fetch read a message of size 0. A QRESYNC `SELECT` leaves such a change out and keeps its checkpoint. The six tests of 2026-11-13 over undecodable lines failed on the new pin before this (their noise is now a partial line) and pass after it, unchanged.
- **io-jmap 0.10** hands an object of a `Foo/get` list it cannot read in `unparsed` where the whole call failed. An `Email/query`, an `Email/get` and a `Mailbox/get` holding one now fail (`parsed`), as the call did before: left out, the object would read as a message or a mailbox gone.
- **io-webdav 0.9 to 0.11**: a card listing names the cards it could not read (`unreadable`), which 0.8 dropped, read as deleted downstream: the listing now fails, as a truncated one does. A multiget's unreadable member is left out, as one the server did not answer, and logged. An id read from an href is percent-decoded where it encodes back to the segment served; every stored id is rebuilt with the store, so nothing compares an earlier spelling.
- The other releases changed no production line: io-http, io-oauth, io-smtp, io-gmail, io-gcal, io-gpeople, io-msgraph, io-gtasks, io-pim-discovery and mime-rs moved their dependencies (pimalaya-stream 0.4's `Tls`, which the app builds none of) or added what the app does not call. Through them, a Google event in a zone other than its calendar's projects in its own zone (io-gcal 0.5.1), a year-less Google birthday reads and writes (io-gpeople 0.8.0), a structural MIME field stated twice reads from its first occurrence (mime-rs 0.2), and a calendar's colour is Apple's namespace (io-webdav 0.10).

## The Graph member left open on 2026-11-15

`a_delta_round_keeps_a_member_its_sent_date_leaves_out` now holds a second member, `sentDateTime` 90 hours back and a `Date` field 10 hours back, inside the 50-hour bound: kept, the folder's ids naming it. spec/offline-store.md loses its shortfall sentence. The test passes on io-pimdir 0.14.1. It could not be seen failing on the old engine: the bridge no longer builds on io-pimdir 0.12, and a scratch copy of 0.14.1 with the old clause put back into `list_unstamped_bindings` still keeps the member, the 0.14 engine filtering a narrowed round's absent set by its enumeration in Rust too (src/sync.rs). The member's loss on 0.12 is the one cairn/log/2026-11-15-a-partial-listing-retires-nothing.md observed.

## Tests

New, in rust/src:

- offline.rs `a_refused_push_is_recorded_then_held`: through io-pimdir's engine, a pending create Java answers refused writes `refusePlacement` (a Bearer token in its reason scrubbed) and reports the refusal; a load carrying it back holds the same change, unpushed, reported again. Failed with the push outcome mapped to `Rejected`: "no refusal recorded".
- mail/imap/tests.rs `a_write_refused_as_it_stands_is_told_from_one_that_may_land`, over the scripted server answering `UID STORE`: `NO [CANNOT]`, `[NOPERM]`, `[TOOBIG]` and `[PARSE]` refused, `NO [OVERQUOTA]`, `NO [INUSE]`, a bare `NO` and a `BAD` not. Failed with `write` setting no refusal.
- mail/jmap/tests.rs `a_roster_holding_a_mailbox_unread_is_refused`: a `Mailbox/get` listing a mailbox whose name is a number. Failed with `parsed` taken out: the roster answered the one mailbox it read.
- error.rs `a_mail_refusal_is_told_by_its_status_and_never_by_a_quota` (Graph, Gmail and JMAP statuses, reasons and codes), and in mail/jmap/tests.rs `an_email_set_on_a_message_gone_answers_404_for_the_caller_to_converge` asserts `tooManyKeywords` refused and `overQuota` not.

New, in the JVM tests: MailMoveTest `aDeleteRefusedAsItStandsIsHeldAndReported`, a delete the fake server refuses as it stands: nothing pushed, the refusal on the binding and in the report, the page landed, and the next sync calls the server for nothing and reports it again. It was not run against the earlier Java, which has no `PimalayaException.refused` to throw.

Changed, for what a release or the drafts correctly changed: the draft names in offline/store.rs, ffi/offline.rs, offline/shape/tests.rs and search/tests.rs (storage-draft-11 and -12 refused, search-draft-11 rebuilt, -14 refused), MailIndexTest, PimdirDbTest and PimdirStorageTest; `SyncReport` literals in ParkedRoundsTest and OfflineClientTest, whose reply test now reads a refusal and `unlisted`.

The sync vector runner (SyncVectorsTest) reads format 7: it seeds and compares a binding's `refusal` and `refused_key`, feeds a `Refused` outcome back with its reason, and the bridge's vector seam (rust/src/ffi/vector.rs) answers a `Refused` event with it. At io-pimdir 0.14.1, 167 cases: 125 pass, 11 fail, 31 are unbound (117, 11 and 31 of 159 at 0.12): the eight new ones pass, 160 and 161 (a narrowed round keeping what its enumeration holds) and 162 to 167 (refusals) among them, through the app's load and write. The ledger did not move.

## Evidence

Each as `nix develop --command <cmd>` from the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.14.1's spec/ (taken by `git archive v0.14.1 spec` from the local io-pimdir clone into the scratchpad), on 2026-11-16:

- `cargo fmt --check` in rust/: clean.
- `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 686 passed, 1 ignored (682 before, four added).
- `gradle -p android :app:testDebugUnitTest :app:assembleDebug --console=plain`: 1,022 tests, 5 skipped, none failing (1,021 before, one added; the first run on the new pin failed the three draft-name tests alone); the five debug APKs built.

Nothing was installed on a device, no live suite was run and nothing connected to an account.

## Left, and for the owner

- **io-gtasks and io-pim-discovery** were taken at 0.2.0 and 0.14.0, the releases of 2026-11-16.
- **No screen shows a refusal.** A refused change is logged every sync and stays on the phone as staged: a message the user deleted stays out of the list though the server holds it, a marker stays set on the phone alone. Showing it beside the message, and offering to drop the change, is a UX step of its own, to be checked on a device.
- **Calendars, contacts and tasks** answer no `Refused`: their pushes keep their own rule (`refused_for_good`, the 422 a driver answers), as before.
- **A refused move** keeps its target's create pending behind the held removal, so the message shows in the target on the phone alone until the user moves it again.
- **A device check**: a draft-11 store opens on the reinstall screen; a fresh install creates the draft-13 store and index on Android 8's SQLite; a refused write over each backend.

## Capabilities moved

- offline-store (cairn/spec/offline-store.md): added "A change refused as it stands is held"; modified "The store's shape follows the canonical schema", "A store of another draft is not opened", "The index is the app's own shape over FTS4", "A narrowed round enumerates its collection" (the shortfall gone, a Graph scenario added) and "The sync vectors run through the app's load and write".
- mail (cairn/spec/mail.md): added "A mail push refused as it stands is held" and "A JMAP answer holding an object it could not read is not whole"; modified "A message's markers can be written" and "An IMAP listing that skipped a response is not whole".
- carddav-sync (cairn/spec/carddav-sync.md): modified "A DAV listing that is not whole is refused".
