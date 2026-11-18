---
cairn: log
change: the-app-follows-neverest-v1
date: 2026-11-18
---

# The stalls and missed syncs (phase S)

Phase S of cairn/changes/the-app-follows-neverest-v1: patchable stalls and mis-syncs. Built on phases B, I and D, uncommitted in the same tree.

## S1. QRESYNC is what the server enabled

rust/src/mail/imap.rs `connect` sent ENABLE and dropped its answer, so a server advertising QRESYNC and answering ENABLE with a bare `OK` (iCloud) got a QRESYNC SELECT on every delta and listed nothing new. The capability is now kept only when the `ENABLED` answer names QRESYNC, without regard to case (`names_qresync`, neverest src/imap/client.rs:124-136); otherwise it is forgotten as a refused ENABLE already was, and every listing takes the full round.

## S2. A Graph or JMAP band lists from its floor

A band's first page was bounded by a ceiling on the provider's date (Graph `sentDateTime lt until`, JMAP `before` at `receivedAt` plus two days), so a message dated in the band but submitted or received after the old floor was listed by neither round. rust/src/mail/msgraph.rs `list_page` now opens a band with no ceiling (`Ceiling::default()`), its later pages still resuming below the oldest `sentDateTime` reached; rust/src/mail/jmap.rs `list_page` drops `before` (JMAP pages by position, so every page keeps one filter). As in neverest (`floor_of`), a widening lists again what the mailbox holds above the band, which the store binds already: the cost of Graph listing 500 messages over four widenings where it listed 200. The floor reads (`floor`) keep their ceiling: they pick a chunk's floor, which lists nothing.

## S3. Gmail's Sent and Drafts are never modified

rust/src/mail/gmail.rs: `FIXED_LABELS` (`SENT`, `DRAFT`); `relocation` answers `GmailRelocation::Refused` for a move into or out of either, but a move into the trash, which stays `messages.trash`; `copy_message` refuses a copy into either; both refusals are `refused: true` before any request, the reason naming Gmail (`fixed_label`). `detach_message` out of either (the account-wide removal) goes through `messages.trash`. A copy out of either adds the target label alone, naming neither, and is carried out, a departure from the task's "into or out of" kept on neverest's live evidence (any `messages.modify` naming either is refused, one naming neither is not).

## S4. A contact, calendar or task push refused as it stands is held

- rust/src/error.rs: `refuses` reads a CardDAV and CalDAV `WebdavSendError::HttpStatus` (and `WebdavFollowRedirectsError::HttpStatus`) whose body names no exceeded quota, and a Calendar, People or Tasks API error, beside Graph, Gmail and JMAP (`google_refuses`, `dav_refuses`, `graph_refuses`); a duplicate `UID` (`WebdavSendError::DuplicateUid`, Fastmail's 403) is no refusal. `BridgeError::refusal` builds the bridge's own refusals for good (422, `refused: true`): `parts_failure`, the occurrence refusals of phase D (rust/src/calendar/occurrence.rs, msgraph.rs, gcal.rs) and JMAP's `refused` (rust/src/client/jmap.rs). A JMAP `CalendarEvent/set` `overQuota` moves to the wait (412), as the mail side reads it.
- rust/src/contact/push.rs: `PushOutcome.refused`, set by the Graph `$batch` (rust/src/contact/msgraph.rs, `graph_refuses` over the inner status, code and message) and by JMAP `ContactCard/set` (rust/src/contact/jmap.rs `refuses`: `forbidden`, `invalidProperties`, `invalidPatch`, `blobNotFound`, `tooLarge`, `singleton`). android/client PushOutcome gains the component, a five-argument constructor kept.
- java/contact/ContactEngine.java `pushOne` answers `refusedResult` for a refused failure that is not a 404, whichever verb raised it, and `pushRound` for a refused outcome. java/calendar/CalendarEngine.java `refused` does the same for a create, an update and a delete, a 422 without the flag read as refused too.
- Phase D's answers keep their meaning: a series create or an occurrence write failing for a reason that may pass answers 412, rejected and staged as before; one refused for good answers 422, now held on its binding and reported rather than pushed again every sync. A 404 stays gone first (a removal converges; an update's 404 still fails the pass, as before), a 412 the stale guard, a create's duplicate `UID` 403 or 409 rejected and staged, `moveInto` rejected. Tasks are pushed by the calendar engine (CalDAV, Google Tasks, To Do), so they follow.

## S5. A floating entry is anchored for Google and Graph

rust/src/calendar/anchor.rs `anchored`, ported from neverest src/calendar.rs (`has_floating`, `anchor_floating`, `anchor_until`): each floating `DTSTART`, `DTEND`, `RECURRENCE-ID`, `EXDATE` and `RDATE` of the events takes the phone's `TZID`, the object takes the phone's `VTIMEZONE` before its first component, and a floating `UNTIL` of a series whose start is floating becomes the UTC instant its wall time names, read off that `VTIMEZONE` by ical-rs's `IcalTz` (the app has no zone database in Rust). With no zone, the times are read in UTC; an object holding no floating time is written as it stands. rust/src/calendar.rs `create_event` and `update_event` anchor for Graph and Google events (not task lists). The zone crosses JNI as it must: `Native.createEvent` and `Native.updateEvent` take a new `anchor` string (rust/src/ffi/calendar.rs, EventCreate and EventUpdate `anchor`), which `CalendarClient` fills from `EventTarget.zone`, a new component with a three-argument constructor kept; java/calendar/CalendarEngine.java computes `Zones.vtimezone(ZoneId.systemDefault(), now)` once a pass. `:app:assembleRelease` was run for the signature (android/client/consumer-rules.pro keeps `Native` whole).

## S6. A DAV drain is bounded and a cut listing is not complete

rust/src/client/webdav.rs `sync_collection` follows a truncated page's token 32 rounds at most (`MAX_SYNC_ROUNDS`, neverest's), and stops on a truncated page naming no new token, where it set the token to none and listed the collection again forever, `changed` piling up. A drain so cut answers `truncated` under the last token known. rust/src/contact.rs `into_card_delta` and rust/src/calendar.rs `into_event_delta` read it as incomplete, and `sync_cards` and `sync_events` keep a DAV round incomplete when cut, so the engine retires nothing it did not list. The change stays inside the drain's end and the completeness of its answer, apart from what PR #1 touches in the same function.

## S7. A Google or Graph delete of an entry already gone is done

rust/src/calendar/gcal.rs `GcalEntry::delete`: a series read answering 404 or 410, a master read as cancelled (Google's tombstone, whose ETag the cancellation moved) and a delete answered 404 or 410 settle the removal, nothing sent. rust/src/calendar/msgraph.rs `delete_event`: a read or a delete answered 404 or 410 settles it. Java's `isGone` (404) and `absent` stay as they were. Task deletes (Google Tasks, To Do) are not changed.

## S8. An SMTP session is not reused after a failed submission

The premise held: rust/src/mail/smtp.rs `send` returned from a refused submission with the connection open past the server's `RSET` answer, and the transport, pooled by origin (android/client Transport.java), handed the next send that connection, whose greeting read timed out. `send` now ends a failed session (`SmtpSession::abandon`): `QUIT` where the server answered, none where the message may have gone with no verdict, then the connection dropped, a failed sign-in included.

## S9. A sending limit keeps the message queued

rust/src/mail/smtp.rs `refusal`: a 5yz naming a sending limit by its enhanced code (`5.4.5`, `5.1.90`, `5.7.232`, `5.7.233`, `5.7.236`) or its wording (neverest src/sync/submit.rs:279-310, `names_a_sending_limit`) is transient: the row stays queued, and the drain stops as for any other failure.

## Tests

Each seen failing on the code before its fix, then passing:

- S1, rust/src/mail/imap/tests.rs over the scripted server (now answering `ENABLE` as scripted and signing in with scripted capabilities): `qresync_not_named_enabled_is_not_used` (`left: 1 right: 0`, the QRESYNC SELECT sent); `qresync_named_enabled_is_used` (`* ENABLED CONDSTORE qresync`) passed before and after, the case the fix keeps.
- S2, rust/src/mail/msgraph/tests.rs `a_band_lists_a_message_sent_after_its_date` through io-pimdir's engine over the fake Graph (`listed by the band its Date falls in`), rust/src/mail/jmap/tests.rs `a_band_lists_a_message_received_after_its_date` (`left: ["near"] right: ["late", "near"]`). `widening_three_times_lists_each_band_once` stated the ceiling (`listed` 200) and now counts the coverage listed again (50 + 100 + 150 + 200), its handles, delta link and delta rows unchanged.
- S3, rust/src/mail/gmail/tests.rs over a Gmail answering a modify naming `SENT` or `DRAFT` 400 "Invalid label": `a_move_or_copy_naming_sent_or_drafts_is_refused_unsent` (`left: [("POST", "/gmail/v1/users/me/messages/m1/modify")] right: []`), `a_removal_from_sent_or_drafts_is_a_trash` (`Gmail API returned HTTP 400: Invalid label: SENT`).
- S4, rust/src/error.rs `a_pim_refusal_is_told_by_its_status_and_never_by_a_quota` (`assertion failed: dav(400, "")`); rust/src/contact/msgraph.rs `a_batched_change_refused_as_it_stands_is_told_refused` and rust/src/contact/jmap.rs `a_card_refused_as_it_stands_is_told_refused` (`("b", false, false)` where `("b", false, true)` was asked). JVM: ContactEngineDavCreateTest `anUpdateRefusedAsItStandsIsHeldAndTheRestPushed` (`PimalayaException: WebDAV server returned HTTP 415`, the book's pass failing), TaskSyncTest `aCreateRefusedAsItStandsIsHeldAndReported` (`PimalayaException: WebDAV server returned HTTP 400`), ContactEnginePushTest `aGraphRoundAnswersAChangeRefusedAsItStandsRefused` (`expected:<...,"refused":"HTTP 400 bad"}]> but was:<...}]>`). ContactClientTest `outcomesReadAsAPushRoundAnswersThem` checks the reply shape and now reads `refused`, as the Rust outcome gained it.
- S5, rust/src/calendar.rs `a_floating_series_is_created_anchored_in_the_phones_zone`, a floating weekly series with `UNTIL=20261231T100000` created through `Calendars::create_event` on a Google and a Graph account over a server answering the create with what it was sent: the error "Google needs a DTSTART carrying either a UTC Z suffix or a TZID"; now Google gets `Europe/Paris` at 10:00 and `UNTIL=20261231T090000Z`, Graph the zone and an end date of 31 December. rust/src/calendar/anchor.rs: three unit tests (summer time in July, no zone read as UTC, a zoned object left as it stands), written with the fix.
- S6, rust/src/client/webdav.rs `a_drain_cut_short_with_no_token_stops_and_says_so` over a server cutting every REPORT short for its first hundred (`101 reports`); rust/src/contact.rs and rust/src/calendar.rs `a_first_round_cut_short_is_not_complete` (`nothing is retired by absence`).
- S7, rust/src/calendar/gcal/tests.rs `a_delete_of_an_entry_already_gone_is_done` (the fake answering a missing event 404 or 410, and a cancelled event's delete 410 as Google does: `left: [(Err(Some(404)), 0), (Err(Some(410)), 0), (Err(Some(412)), 0)]`), rust/src/calendar/msgraph/tests.rs `a_delete_of_an_event_already_gone_is_done` (`left: [(404, 204, Err(Some(404)), 0), (410, 204, Err(Some(410)), 0), (200, 404, Err(Some(404)), 1), (200, 410, Err(Some(410)), 1)]`).
- S8, rust/src/mail/smtp.rs `a_submission_refused_leaves_the_next_one_sent`, two `send`s over one scripted SMTP connection kept between them as Java's transport keeps it, the first refused at `RCPT` 550: `left: Err("Read timed out") right: Ok(())`.
- S9, rust/src/mail/smtp.rs `a_sending_limit_keeps_the_message_queued` over the same server answering `550 5.4.5 Daily user sending limit exceeded`: `Recipient <limited@example.org>: SMTP RCPT TO failed: rejected 550 5.4.5 Daily user sending limit exceeded` read as permanent.

## Evidence

From the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.16.0's spec/, on 2026-11-18:

- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 730 passed, 1 ignored (710 before, 20 new: S1 2, S2 2, S3 2, S4 3, S5 4, S6 3, S7 2, S8 1, S9 1).
- `gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain`: 1,031 tests (1,028 before, 3 new: ContactEngineDavCreateTest, ContactEnginePushTest, TaskSyncTest), 5 skipped, none failing, the debug and release builds assembled (`Native.createEvent` and `Native.updateEvent` take one string more, and the push outcome one field more). SyncVectorsTest: 167 cases, 126 pass, 10 fail, 31 unbound, the ledger unchanged and no vector moving. A first run without `PIMALAYA_IO_PIMDIR_SPEC` failed SyncVectorsTest and PimdirHashTest for want of the vectors alone, and was run again with it.
- `cairn/verify.sh`: conformant.

Nothing was installed on a device and nothing connected to an account.

## Left

- A People batch create or delete (`createCards`, `deleteCards`) refused whole still fails the book's round: no one card of the batch can be told refused.
- A move into another calendar (`moveInto`) keeps its rule: a refusal of the move or of the update after it is rejected, not refused.
- An update answered 404 still fails the pass, as before; Google Tasks and To Do deletes still take a 410 as a failure.
- The anchoring `VTIMEZONE` starts the year before the pass: a floating `UNTIL` in an earlier year is read with the earliest observance it states.
- A widening on Graph and JMAP lists again the mailbox above its band; a large mailbox widened many times pays for it in requests, not in bodies.
- `smtp::check` (the setup's sign-in check) still leaves a failed session's connection open.
- The live checks of tasks.md §G1 for S1 (iCloud), S3 (Gmail Sent) and S5 (a floating series copied to Google and Graph), for the owner.
- During S6, a Perl substitution whose replacement opened a brace corrupted rust/src/calendar.rs (its own text interpolated into it three times). The file was rebuilt from one of those embedded copies, checked identical to the other two and to the file's own unchanged prefix, then the edit made again by hand; git was not used. Worth a look at review.

## Capabilities moved

- mail: modified "An extension is enabled before it is used" (QRESYNC as `ENABLED` names it, a scenario), "An account bounds its mail" (a band from its floor, a scenario), "A message is moved or copied" (Gmail's Sent and Drafts, a scenario), "A message is deleted into the account's trash" (out of Sent or Drafts by `messages.trash`), "A message is submitted and a copy is kept" (a sending limit, a failed session ended, two scenarios).
- carddav-sync: added "A contact push refused as it stands is held", "A DAV listing is bounded".
- calendar: modified "A calendar entry can be deleted" (Google and Graph gone or cancelled, a scenario), "A series is created whole" (422 held); added "A calendar push refused as it stands is held", "A floating entry is anchored for Google and Graph", "A calendar listing is bounded".
- tasks: added "A task push refused as it stands is held".
- offline-store: modified "A change refused as it stands is held" (every source answers so).
