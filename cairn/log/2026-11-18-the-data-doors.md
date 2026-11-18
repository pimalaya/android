---
cairn: log
change: the-app-follows-neverest-v1
date: 2026-11-18
---

# The data doors (phase D)

Phase D of cairn/changes/the-app-follows-neverest-v1: a loss or a duplicate on a server. Built on phases B and I, uncommitted in the same tree. D6 is phase I's Left made a task (owner decision Q2).

## D1. A send that may have gone is not sent again

An HTTP send was uncertain only when no byte of its answer came (`Client::unanswered`), so a Gmail 200 whose body did not parse (`GmailSendError::ParseResponse`, no status), a Graph 202 cut after its status line and a JMAP `EmailSubmission/set` answered with a body that did not parse fell through as transient: the row stayed queued and the next drain sent it again. A failure before the submission's own request was the opposite: a JMAP connection lost at `Identity/get` read as uncertain.

The verdict is now told by the failure, per backend, as neverest's `gmail_disposition` does (src/sync/submit.rs):

- rust/src/mail.rs `SendFailure`: the error and `taken`, whether the request went whole with no verdict read back; `From<BridgeError>` for a failure before the send's own request.
- rust/src/client/throttle.rs: `Client::sent` (the request written whole, its answer awaited) replaces `unanswered`, and `Exchange.heard` goes.
- rust/src/mail/gmail.rs and rust/src/mail/msgraph.rs `send_message`: a request never built (`ParseUrl`, `SerializeRequest`, `InvalidRequest`) or answered with a status (`Api`) is not taken; any other coroutine failure is; a transport failure is taken when the request had gone whole.
- rust/src/mail/jmap.rs: every failure before `EmailSubmission/set` is not taken; the submission is taken unless its request was not built or the server answered with a status or a method error.
- rust/src/mail/session.rs `Refused::http` reads `SendFailure`; a status keeps deciding as before (5xx uncertain, the refusal codes permanent, a rate or quota pending). A Gmail 403 naming no rate still parks the message as refused (owner decision Q3).

The Java queue already parks an uncertain row and never hands it over again (SyncPasses, MailStore), so nothing changed there.

## D2. A Google contact update keeps what the vCard cannot hold

rust/src/contact/gpeople.rs `update_card` read the person only for a missing etag or a `clientData` change and sent the projection for every masked field, overlaying foreign `clientData` by hand. It now reads the person before every update and sends `GpeoplePerson::merge_unprojected(&read, &fields)` (io-gpeople 0.8.1), the hand overlay gone, the three-way `merged_fields` guard kept. The merge alone does not keep an HTML biography when `biographies` is masked (io-gpeople keeps only a non-HTML one), so an update with no base, which masked every field, now masks only the fields its vCard shows apart from the person read. One more `people.get` per update where the etag was known.

## D3. A new series is created whole on Graph and Google

- rust/src/calendar/occurrence.rs: `set_apart` (overrides, `EXDATE`s), `refuse_ranges` (`RANGE=THISANDFUTURE` refused, 422), `unattended` (the series' attendees off the master and off each override naming the same ones), `no_instance` (an override of an occurrence the series lacks, 422).
- rust/src/calendar/msgraph.rs `create_event`: a series setting anything apart is posted without attendees (the `transactionId` kept), then `GraphEntry::write_occurrences`, then `invite` patches the attendees in; a failure deletes the master and answers 412, or 422 for a refused occurrence.
- rust/src/calendar/gcal.rs `create_entry`: a series with overrides that the user organizes is inserted with `sendUpdates=none` and no attendees, its overrides written telling nobody, then `GcalEntry::invite` replaces the master with its attendees under `sendUpdates=all`; one imported writes its overrides the same way. A failure deletes the series, telling nobody unless the invitation went. The 409 read-back by `iCalUID` is kept. `EXDATE`s ride in the master's recurrence, as before.
- Both `write_occurrence`s refuse an override with no matching instance where they logged it and answered `Ok`, and both `update`s refuse `THISANDFUTURE`, on updates as on creates, as neverest does.

## D4. A multiget row not answered as gone keeps its member

rust/src/contact/carddav.rs `multiget_cards` and rust/src/calendar/caldav.rs `multiget_events` left out every unreadable row, which `PimdirEngine.named` then left out of a complete round's page, retiring the member. A row answered 404 or 410 is still left out (gone); any other is read alone by `GET` and kept, or, still unreadable, fails the fetch (rust/src/client/webdav.rs `gone_row`, `is_gone`, `unreadable`). The io-webdav row skip in `sync-collection` stays for phase X.

## D5. An IMAP session out of step is opened again

rust/src/mail/imap.rs `stream_batch` answered `Refusal::Failed` for every batch error but `NO` and `BAD`, the rest of the answer left on the socket, and nothing reset the session. Such an error is now `Refusal::OutOfStep`: `ImapSession::reopen` drops the connection, starts a fresh `ImapState` and signs in again with the credential `ImapSession::signed` was bound with (rust/src/mail/session.rs `MailSession::imap` binds every verb so), and the batch is read one message after another. `UidMissing` also sets `ImapState.alone`, which survives the reopen, so every later batch of the session is read one message at a time, as io-imap's doc asks. A session bound without a credential (`ImapSession::bind`) is marked `out_of_step` and opens again on its next `signed` bind.

## D6. A copy of a body that is not UTF-8 is refused

The contact editor ticking a card into another book (java/contact/ContactWriter.java `addToBook`) staged the editor's text, U+FFFD where the bytes were, as a new card there; its push was not refused, that body being well-formed UTF-8. java/pimdir/Stager.java `requireText` is the refusal `mutateResolve` made inline; java/contact/PimdirContacts.java `copy` calls it on every placement the card is copied from before staging, and ContactEditor `createCopy` and ContactWriter `addToBook` go through it.

## Tests

Each seen failing on the code before its fix, then passing:

- D1, rust/src/mail/session.rs, through `Mail::submit` over the fake HTTP server: `a_gmail_send_answered_200_unparsed_is_parked_uncertain` (`Gmail response parsing failed: expected value at line 1 column 1 (permanent false, uncertain false)`), `a_graph_send_cut_after_its_status_is_parked_uncertain` (`Microsoft Graph HTTP request failed: HTTP/1.1 send failed: reached unexpected EOF (permanent false, uncertain false)`), `a_jmap_submission_answered_200_unparsed_is_parked_uncertain` (`JMAP EmailSubmission/set failed: JMAP send failed: parse response: ... (permanent false, uncertain false)`; its second half, a connection lost at `Identity/get` left pending, was not run on the old code). rust/src/client/fake.rs gained `FakeResponse::cut` (a head, then the connection dropping). rust/src/client/throttle.rs `a_request_without_an_answer_is_unanswered` stated the old flag and is restated as `a_request_written_whole_is_sent`.
- D2, rust/src/contact/gpeople.rs `an_update_keeps_the_organizations_the_card_cannot_hold` and `an_update_with_no_base_keeps_what_the_card_cannot_hold`: the PATCH carried `"organizations":[{"name":"Acme","title":"Lead"}]` where People held a location and a second organization. With the merge in, the second test still failed on the HTML biography (`left: Null`), which the narrowed mask fixed. The fake `holding` now applies an update as People does, computing the output-only `displayName` again from the name (`applied`): the merge sends the read `displayName` back, which People ignores, and without it `a_listing_etag_is_read_again_and_the_edit_sent_under_it` and `an_edit_elsewhere_of_another_field_merges` read the old name; the two assertions they make are unchanged.
- D3, rust/src/calendar/msgraph/tests.rs (`write` module) and rust/src/calendar/gcal/tests.rs: `a_new_series_is_created_whole_its_attendees_last` (`left: [("POST", "cal")]`), `a_new_series_is_created_whole_its_guests_invited_last` (`left: [("INSERT", "m")]`), `a_new_series_overriding_no_occurrence_is_refused_and_deleted` and `a_new_series_overriding_this_and_future_is_refused_unwritten` on each (`created: [("POST", "cal")]`, `created: [("INSERT", "m")]`). The Graph fake answers a create as the posted series; the Google fake files a created event under `created_id` and keeps it.
- D4, rust/src/contact/carddav.rs and rust/src/calendar/caldav.rs `a_multiget_row_refused_is_read_alone` (`left: ["c1.vcf"] right: ["c1.vcf", "c2.vcf"]`, `left: ["e1.ics"]`) and `a_multiget_row_unreadable_alone_fails_the_read` (`the member would be retired`).
- D5, rust/src/mail/session.rs over the scripted IMAP server (rust/src/mail/imap/tests.rs, now `pub` to the crate's tests, its `Scripted` answering `AUTHENTICATE PLAIN` and greeting again on a reset): `an_imap_batch_failing_mid_answer_opens_the_connection_again`, run with the batch errors mapped back to `Refusal::Failed` as before the fix: `left: (Err("IMAP batched FETCH failed: body of UID 2 announces 10000000 octets, over 65672"), Err("IMAP FETCH failed: Reached unexpected EOF on IMAP stream"))`, the second read reading the first's leftovers; `an_imap_batch_naming_no_uid_is_read_one_message_after_another`: `left: (Err("IMAP batched FETCH failed: FETCH body line without a parseable UID"), Err(...))`.
- D6, android/app/src/test/java/org/pimalaya/contact/ContactEngineDavCreateTest.java `aCardThatIsNotUtf8IsNotCopiedIntoAnotherBook`, through the real editor (Robolectric), the card ticked into a second book of the account: `expected:<[https://dav.example.com/books/created/]> but was:<[https://dav.example.com/books/created/, https://dav.example.com/books/other/]>`. The refusal is `requireText`'s, moved out of `mutateResolve` unchanged but for its message, which now says "edited or copied".

## Evidence

From the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.16.0's spec/, on 2026-11-18:

- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 710 passed, 1 ignored (693 before, 17 new: D1 3, D2 2, D3 6, D4 4, D5 2).
- `gradle -p android :app:testDebugUnitTest :app:assembleDebug --console=plain`: 1,028 tests (1,027 before, 1 new), 5 skipped, none failing, the debug build assembled. No JNI-called signature nor reply shape moved, so `:app:assembleRelease` was not run. SyncVectorsTest: 167 cases, 126 pass, 10 fail, 31 unbound, the ledger unchanged and no vector moving.
- `cairn/verify.sh`: conformant.

Nothing was installed on a device and nothing connected to an account.

## Left

- Graph answers a create naming a `transactionId` it saw with the event that create made (calendar "A Graph event is created once"); whether it does so for a series D3 deleted again, which the retry names alike, is not known and not checked live. If it does, the retry would write occurrences to a deleted event and stay rejected.
- A task moved to another list (java/task/TaskWrites.java `move`) and an entry's this-and-following split still stage the text of their source; a source whose body is not UTF-8 is refused only where an edit of it is staged in the same transaction (the split's master), not for a task move. A contact merge writing a survivor from a replica that is not UTF-8 is not refused either.
- D5 covers the batched body fetch; another IMAP command failing with its answer part unread (a parse failure through `ImapSession::run`) still leaves the session as it is.
- D1's per-variant reading and D5's reopen are the helpers phase X lists for io-http and io-imap.
- The live checks of tasks.md §G1 for D2 and D3, for the owner.

## Capabilities moved

- mail: modified "A send that may have gone waits for the user" (told by the failure, two scenarios); added "An IMAP session out of step is opened again".
- carddav-sync: modified "A DAV listing that is not whole is refused" (a multiget row read alone), "A contact update never writes over a remote change" (merged over the person read, a scenario), "A card that is not UTF-8 is kept as served" (a copy refused, a scenario).
- calendar: added "A series is created whole", "A multiget row not answered as gone keeps its entry".
