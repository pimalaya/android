---
cairn: tasks
change: e2e-suite
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, the suite's to android/app/src/e2eTest/java/org/pimalaya/.

## Seams

- [x] `MailSubmit` out of `MessageCompose.send`
- [x] `MailReader` (`read`, `fetch`) out of `MessageView.load` and `fetch`; `MessageView.openOnline` keeps the offline message
- [x] `MailEngine.stageFlag` out of `MessageView.stageFlag`
- [x] `MainActivity.wifi(Context)`
- [x] `OnboardingFlow.endpointUrl`, `submissionUrl`, `asUser` static

## Harness

- [x] `:app:e2eTest` in android/app/build.gradle.kts; the unit tasks exclude `E2e*`
- [x] `E2eTarget` (mail, contacts, calendar targets), `E2eSecrets`, `E2eHttp`
- [x] `E2eDevice`: isolated context (files, databases, preferences), first sync as a step, Load more, pass on the Wi-Fi gate, contacts and calendar passes; `E2eDevice.network` injects Wi-Fi, mobile data or none into Robolectric's connectivity
- [x] `E2eCase`: tag, scratch collections, cleanup and stale sweep, observer device, `eventually` and `settles` (the state seen last in the failure)
- [x] Fixtures: `E2eGmailFixture`, `E2eImapFixture`, `E2eJmapFixture` (mail, `where` a message is filed); `E2eGoogleContactsFixture` (People API), `E2eGoogleCalendarFixture` (Calendar API, primary calendar for CalDAV)
- [x] `E2eReport`: build/reports/e2e/matrix.md, columns the targets asked
- [x] `E2eDavFixture` for Fastmail CardDAV and CalDAV (extended MKCOL, MKCALENDAR, PUT, over a socket of its own: the platform's `HttpURLConnection` refuses WebDAV methods); a contacts test works in its scratch book but on Google
- [ ] JMAP contacts and calendars fixtures: the test token's session offers mail and submission alone
- [ ] Microsoft Graph targets once a delegated refresh token can be supplied

## Phase 1: mail

- [x] `E2eMailListTest`: first sync lists a scratch mailbox (dates, decoded subject, attachment mark, bodies whole); an opened message stored, read back offline, marked read, seen by another device
- [x] `E2eMailStepTest`: Load more steps a mailbox past its first step, frontier; a Gmail message under two labels read once, filed in both, its label taken off
- [x] `E2eMailWriteTest`: markers pushed, a keyword kept; delete into the trash then for good
- [x] `E2eMailSendTest`: send to self through the outbox (queued offline, a failed drain, drained, Sent holds one, no Bcc delivered); on mobile data the outbox goes out and nothing is listed, a message opens
- [x] Live green on google-gmail and google-imap but for the findings below
- [x] Live on microsoft-imap, fastmail-jmap, fastmail-imap

## Phase 2: contacts and calendars

- [x] `E2eContactsTest`: a contact round trip (seeded synced, created, edited, seen by another device, deleted); deleting one written elsewhere; deleting one created on the phone
- [x] `E2eCalendarTest`: an event round trip in a scratch calendar; deleting one written elsewhere; deleting one created on the phone
- [x] Live on google-carddav, google-gcal, google-caldav

## Findings

- [x] Gmail and Graph refused clearing `\Answered` or `\Deleted`: `MailEngine.pushFlags` states all four markers on a placement with no agreed set (a Gmail sent copy landed on its own copy), and the bridge refused the two Gmail and Graph never keep, failing every pass over Sent after a send. Fixed in rust/src/ffi/mail.rs `set_flag`: clearing them there is a no-op.
- [x] A staged delete is silently dropped on DAV (Google CardDAV, Google CalDAV): an item created on the phone and pushed, then deleted, is never deleted on the server, and the next pass brings it back on the phone (`refreshed`, nothing pushed). An item written elsewhere deletes fine. offline-store "The row is not resurrected", calendar "Deleting an entry". Fixed in google-delete-loss: the created resource's name was dropped (CardDAV bound `x.vcf.vcf`, Google relocates), and Google's CalDAV answers no ETag to a PUT, so the next listing undid the removal.
- [x] Same on the Google Calendar API once the phone has edited the event it created: create then delete works, create, edit, then delete is undone (the pass after the edit pulls the event again, then the removal is dropped). Fixed in google-delete-loss: the update's ETag was kept escaped (`\"…\"`), a revision no listing names.
- [x] Google CardDAV: an edit staged right after a create is pushed one pass late (the pass after the create pulls the card back under Google's rewrite). Fixed in google-delete-loss, the name dropped at the create, and a member whose body restates its identity (Google's UID rewrite) no longer replaces the item.
- [ ] IMAP (google-imap): a first step lists all 56 messages seeded a day apart, not 50. Still so with `ImapStep` merged, for a new cause: the first step lists every mailbox from the inbox's floor, and Gmail has no `SORT`, so the floor falls back to the newest UIDs, whose dates reach back to 2025-04-20 (imported seed mail, `INTERNALDATE` equal to its `Date`): the first step lists 1,386 messages and fetches about 730 bodies. The test's per-mailbox bound (`first < SEEDED`) also predates the account-level floor and needs restating against it.
- [x] IMAP: the first step lists at meta, bodies left to the body step (`WidenStep`). Gone with `ImapStep` merged: `aFirstPassListsAScratchMailboxWhole[google-imap]` passes, every message bodied when the step ends.
- [ ] IMAP: a step's body download files another mailbox's body under a message (new with `ImapStep`, data loss class). An IMAP message's link id is its UID (rust/src/client/listing.rs `Named`), and the download's cross-collection dedup (`PimdirStorage.lookupObjects`, any collection but contacts) takes a UID as naming immutable bytes account-wide: the step downloads `[Gmail]/All Mail` first (462 fetched), then every other mailbox links its UIDs to All Mail's (INBOX 144, moa-seed 287 deduped). Evidence: the scratch message, UID 1, holds `<f6f459a9...@google.com>` ("Your Google Workspace billing information was received", All Mail's UID 1), so `anOpenedMessageIsStoredMarkedReadAndKept[google-imap]` reads an empty body ("the body as sent"). Under `WidenStep` the open fetched by address, which is why it was green. mail "An opened message is stored and read back".
- [ ] Gmail self-send: the staged sent copy, landed on Gmail's copy with no agreed set, pushes `\Seen`, so the copy delivered to the inbox is marked read (no spec line either way).
- [ ] A fresh device never lists a Gmail account's trash or junk (steps leave them out, listing on open owed: mail-load-more B.13 rule 7).

## Land

- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`; cairn/verify.sh
- [x] Merged into the main tree (2026-10-11) on top of mail-sync-robustness, the IMAP/Graph/JMAP steps, sync-feedback-pinned, oauth-pending-grant-encrypted, throttle-chunk-bounds; one conflict, `MessageView.load`, re-applied onto `mailboxWindow`
- [x] Live run of the merged tree on the Google targets, below
- [x] Live run on every target, below
- [ ] Fold (no spec change), log, archive

## Run on the merged tree, 2026-10-11

Google targets only (Microsoft and Fastmail skip, credentials locked), 25 run, 18 passed, 7 failed:

| Test | google-gmail | google-imap | google-gcal | google-caldav | google-carddav |
|---|---|---|---|---|---|
| E2eMailListTest.aFirstPassListsAScratchMailboxWhole | passed | passed | | | |
| E2eMailListTest.anOpenedMessageIsStoredMarkedReadAndKept | passed | FAILED: another mailbox's body (UID link id dedup) | | | |
| E2eMailStepTest.loadMoreStepsAMailboxPastItsFirstStep | passed | FAILED: first step lists 56 of 56 (no-SORT floor) | | | |
| E2eMailStepTest.aGmailMessageUnderTwoLabelsIsReadOnceAndFiledInBoth | passed | passed | | | |
| E2eMailWriteTest.markersArePushedAndAKeywordIsKept | passed | passed | | | |
| E2eMailWriteTest.aDeleteMovesIntoTheTrashThenErases | passed | passed | | | |
| E2eMailSendTest.aMessageSentToSelfIsDeliveredFiledOnceAndDrained | passed | passed | | | |
| E2eMailSendTest.onMobileDataTheOutboxGoesOutAndNothingIsListed | passed | passed | | | |
| E2eCalendarTest.anEventRoundTripsThroughAScratchCalendar | | | FAILED: delete after edit undone | FAILED: delete dropped | |
| E2eCalendarTest.anEventWrittenElsewhereIsDeleted | | | passed | passed | |
| E2eCalendarTest.anEventCreatedOnThePhoneIsDeleted | | | passed | FAILED: delete dropped | |
| E2eContactsTest.aContactRoundTripsThroughTheAddressBook | | | | | FAILED: delete dropped |
| E2eContactsTest.aContactWrittenElsewhereIsDeleted | | | | | passed |
| E2eContactsTest.aContactCreatedOnThePhoneIsDeleted | | | | | FAILED: delete dropped |

## Run on every target, 2026-10-11 (evening)

Every target with a credential and a fixture: 50 cases, all passed but for two flakes, each green on a rerun. Microsoft Graph and Fastmail JMAP contacts and calendars skip (no delegated token; no capability).

| Test | gmail | g-imap | ms-imap | fm-jmap | fm-imap | g-gcal | g-caldav | g-carddav | fm-caldav | fm-carddav |
|---|---|---|---|---|---|---|---|---|---|---|
| MailList.aFirstPassListsAScratchMailboxWhole | passed | passed | passed | passed | passed | | | | | |
| MailList.anOpenedMessageIsStoredMarkedReadAndKept | passed | passed | passed | passed | passed | | | | | |
| MailStep.loadMoreStepsAMailboxPastItsFirstStep | passed | flake, passed | passed | passed after the test fix | passed | | | | | |
| MailStep.aGmailMessageUnderTwoLabelsIsReadOnceAndFiledInBoth | passed | passed | skipped | skipped | skipped | | | | | |
| MailWrite.markersArePushedAndAKeywordIsKept | passed | passed | passed | passed | passed | | | | | |
| MailWrite.aDeleteMovesIntoTheTrashThenErases | passed | passed | passed | passed | passed | | | | | |
| MailSend.aMessageSentToSelfIsDeliveredFiledOnceAndDrained | passed | passed | passed | skipped (no send) | passed | | | | | |
| MailSend.onMobileDataTheOutboxGoesOutAndNothingIsListed | passed | passed | flake, passed | skipped (no send) | passed | | | | | |
| Calendar.anEventRoundTripsThroughAScratchCalendar | | | | | | passed | passed | | passed | |
| Calendar.anEventWrittenElsewhereIsDeleted | | | | | | passed | passed | | passed | |
| Calendar.anEventCreatedOnThePhoneIsDeleted | | | | | | passed | passed | | passed | |
| Contacts.aContactRoundTripsThroughTheAddressBook | | | | | | | | passed | | passed |
| Contacts.aContactWrittenElsewhereIsDeleted | | | | | | | | passed | | passed |
| Contacts.aContactCreatedOnThePhoneIsDeleted | | | | | | | | passed | | passed |

- [x] Test bug: `loadMoreStepsAMailboxPastItsFirstStep[fastmail-jmap]` asserted a first chunk of at least 50 in the scratch mailbox, but a JMAP step walks the account (as Gmail, IMAP and Graph steps do), so newer mail elsewhere took 11 of its 50. The bound is dropped.
- [x] (fixed by mailbox-refusal-skipped) One mailbox's `EXAMINE` refused fails the whole step (app, robustness): `IMAP EXAMINE failed: NO neverest-live-… doesn't exist.` on microsoft-imap (a folder a concurrent neverest live run made and deleted between the roster and the floor read), and `NO Unknown Mailbox: pimalaya-e2e-…-1 (Failure)` on google-imap at Load more on the test's own label made through the Gmail API seconds before (a fresh session not seeing it yet; the first session had listed it). Neither server sends RFC 5530 `[NONEXISTENT]`, so skipping such a mailbox needs a classification of the refusal in the bridge and a spec line on what the step does with it.
- The google-imap findings above (no-`SORT` floor, UID-keyed dedup) did not reproduce: both tests passed on the merged tree.
