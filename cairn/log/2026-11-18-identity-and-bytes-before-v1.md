---
cairn: log
change: the-app-follows-neverest-v1
date: 2026-11-18
---

# Identity and bytes before v1 (phase I)

Phase I of cairn/changes/the-app-follows-neverest-v1, the one-way doors: what a v1 store keeps for good. The owner took every recommendation on 2026-11-18 (proposal.md, Decided): Q1 follow pimdir STORAGE §9 for card, entry and task link ids; Q2 store a DAV body's served bytes and refuse to push a phone edit of a body that is not UTF-8.

## I1. Link ids follow STORAGE §9

**The engine's part, read first.** io-pimdir 0.16.0's sync engine takes the `link_id` a listed or fetched member carries as the hint, verbatim (src/sync.rs `name`, `PimdirRemoteMeta::link_id`), and does one thing of §9's table itself: `PimdirLinkId::claim` mints `dup:<key>#<handle>` over a key the collection already holds (src/placement.rs). It does not escape (`PimdirLinkId::from_hint`) nor fall back to `hash:` (`summary::uid_key`): those are `summary::derive`'s, and the bridge built the member's key itself (rust/src/offline/wire.rs `RemoteItemJson`, `PimdirLinkId(...)` over the JSON string). `PimdirMutation::Add` takes its key verbatim too, refusing a live collision. So the app was the one departing from §9: a card was keyed by its raw `UID` or, without one, by its handle (ContactEngine, PhoneRemote), an entry and a task by their resource name (CalendarEngine `entry`, CalendarRemote), a create by `uid + ".ics"` or a random UUID.

**What changed.** Every card, entry and task is now keyed by what io-pimdir derives from its body:

- rust/src/contact/fields.rs: `CardIndex` carries `link_id`, io-pimdir's `derived.link_id`; android/client CardIndex mirrors it.
- java/contact/ContactEngine.java (`fetchedItem`), java/contact/PhoneRemote.java (`read`): the member's `linkId` is `index.linkId()`.
- java/contact/ContactWriter.java, java/contact/VcfTransfer.java: a new card, a copy into another book and an import are staged under that key (`ContactWriter.keyOf`), a random UUID only for a card the bridge cannot read.
- java/calendar/CalendarEngine.java (`entry`), java/calendar/CalendarRemote.java (`read`): the `linkId` is io-pimdir's derived key (`CalendarSummaries.keyOf`), the handle only where nothing derives.
- java/calendar/EventView.java, java/calendar/EventWrites.java (a split's new series), java/task/TaskWrites.java (`create`): a create is staged under its `UID`'s key, no longer `uid + ".ics"`.
- java/pimdir/PimdirStorage.java `resourceOf`: a create is filed under its stated `UID` (the one leading `esc:` stripped), a key the writer derived (`hash:`, `dup:`, `alt:`) under a fresh UUID name; CalendarEngine adds `.ics` (not doubled for a create staged under the old `uid.ics` key), ContactEngine `.vcf` as before.

A member its source already binds keeps the key the store holds (PimdirEngine `keepBoundNames`, `named`), unchanged: a store synced before keeps its resource-name keys until it is rebuilt (phase V rebuilds every store), with no churn, and a `hash:` key does not move when a UID-less card is edited, a departure from §9 now stated in offline-store. The phone's own handles (`_SYNC_ID`, `SOURCE_ID`) are opaque names the app owns and need no change: a store-made create lands on the phone under its key, a phone-made one keeps its random handle while its key is its `UID`.

## I2. A folder's key escapes its own name

rust/src/mail/msgraph.rs `list_mailboxes` built `{path}/{display_name}` unescaped. Each name is now a segment with `%` as `%25` and `/` as `%2F` (rust/src/mail.rs `path_segment`, ported from neverest src/msgraph/client.rs `folder_segment`). The same flaw was in rust/src/mail/jmap.rs `mailbox_path` (a JMAP mailbox named `A/B` against `B` under `A`), fixed the same way: one class, one guard.

`Mailbox` gains `label`, the path with the escapes undone (`path_label`), the key itself on IMAP and Gmail; android/client Mailbox mirrors it with a two-argument constructor kept for the tests. java/mail/MailEngine.java stores the label as the collection's name and sorts by it; java/setup/OnboardingFlow.java lists the label. No reader splits a key on `/`: the drawer and the move picker show the stored name or the role's label, and address by collection id. One reader used the stored name as a key: java/mail/MailStore.java `row` handed `StoredMessage.mailbox` (which MailReader passes to the bridge and the trash checks compare) from the collection's name; it now reads the key off the collection id.

## I3. A JMAP message is named from its header bytes

io-jmap 0.10 offers RFC 8621's raw `headers` property (`JmapEmailProperty::Headers`, `JmapEmail::headers`), which is enough: no library change. rust/src/mail/jmap.rs asks for it in the listing's and the step's `Email/get` in place of `messageId`, `inReplyTo`, `from`, `to`, `cc` and `subject` (`sentAt` stays, which a step and a band page by), joins the fields into the header block a fetched body starts with (`name:` and the raw value, which keeps its leading space and folds), and names the message by `derive_meta` with the server's `size` and `hasAttachment`. The stray `decode` of an already decoded subject went with the old summary. L3's Graph fallback is untouched.

## I4. A DAV body is stored as served

**The path, traced.** A CardDAV or CalDAV body crossed as text: io-webdav 0.11 decodes a whole `multistatus` answer as UTF-8, replacing each ill-formed sequence with U+FFFD, before it parses it (src/rfc4918.rs `body_text`, used by every REPORT and PROPFIND), so a multiget's `data` is already lossy UTF-8; rust/src/contact/carddav.rs and rust/src/calendar/caldav.rs then ran `String::from_utf8_lossy` again; the `Card.vcard` or `Event.ical` string crossed the JNI as JSON; Java hashed its UTF-8 (`CardStore.byteHash`, `PimdirHash.of`) and the engine wrote those bytes into the blob store. Only a GET (`CarddavCardRead`, `CaldavItemRead`) hands the bytes as served.

**What changed.**

- rust/src/client/webdav.rs `served`: a body's text, and its bytes in base64 where they are not UTF-8; `maybe_lossy`: a multiget body holding U+FFFD is read again by GET (carddav.rs `multiget_cards`, caldav.rs `multiget_events`), whose bytes the server sends as stored. `Card` and `Event` carry `vcardBase64` and `icalBase64` (omitted when absent); the Java records mirror them with a `bytes()` accessor.
- rust/src/offline/wire.rs: a listed member may carry `bodyBase64`, as a fetched one already could.
- java/pimdir/PimdirEngine.java `putBody`: a server body is hashed as its bytes and sent as `bodyBase64` where they are not UTF-8, a UID-less one keyed from those bytes; `named` carries `bodyBase64` through the naming read.
- java/pimdir/PimdirStorage.java `loadRow` states `object`, `baseObject`, and `lossy` and `baseLossy` for a body that is not well-formed UTF-8 (`wellFormed`); `base64Of` reads the stored bytes.
- Refusals: java/pimdir/Stager.java refuses a content edit of a lossy body before staging (a refusal `PimalayaException`, which the contact and calendar triage already catch); java/contact/PhoneRemote.java and java/calendar/CalendarRemote.java put the phone's rows back from the store on a phone edit of a lossy base, and hand the stored bytes for an unedited one (else the phone would report the lossy text as an edit); ContactEngine and CalendarEngine answer any add or update whose stored body is lossy refused at the push (`withoutLossy`, `PimdirEngine.NOT_UTF8`). Each is logged.

## Tests

Each seen failing on the code before its fix, then passing:

- I1, android/app/src/test/java/org/pimalaya/contact/ContactEngineDavCreateTest.java `aListedCardIsKeyedAsStorageNineAssigns`: `expected:<[esc:]hash:0123> but was:<[]hash:0123>`. android/app/src/test/java/org/pimalaya/calendar/CalendarDavWriteTest.java `anEntryIsKeyedByItsUidAndASecondResourceIsMinted`: `expected:<[dup:e-1#b.ics, e-1, esc:hash:0123]> but was:<[a.ics, b.ics, c.ics]>`. rust/src/contact/fields.rs `index_keys_the_card_as_storage_assigns` (new field). `aCreateUnderAnEscapedUidLandsOnItsListing` passed before and after: the push already stripped `esc:`; its first expectation was wrong (the fake account names resources, so the card is `hash:0123.vcf`) and was corrected.
- I2, rust/src/mail/msgraph/tests.rs `a_slash_in_a_folders_own_name_keys_a_collection_of_its_own` and rust/src/mail/jmap/tests.rs `a_slash_in_a_mailboxs_own_name_keys_a_collection_of_its_own`, run with the escape made the identity (the old code's keys): `left: [("a", "A"), ("ab", "A/B"), ("b", "A/B"), ("pc", "100%")] right: [("a", "A"), ("ab", "A%2FB"), ("b", "A/B"), ("pc", "100%25")]`.
- I3, rust/src/mail/jmap/tests.rs `a_message_is_named_from_its_header_bytes`: the summary read `"date": "2026-10-10T11:00:00Z"`, `"subject": "x"` where `derive_meta` over the same header bytes gives `12:00:00Z` (military `A`, read as `-0000`) and `=?utf-8?q?x?=`. The test's last line first read `summary["subject"]` where the JSON nests under `mail`; fixed in the test.
- I4, rust/src/contact/carddav.rs `a_card_read_whole_keeps_the_bytes_served`, rust/src/calendar/caldav.rs `an_entry_read_whole_keeps_the_bytes_served`: `left: []` (no `vcardBase64`, `icalBase64`) against the served bytes holding 233. The scripted server first answered every request with the multistatus; it now answers a GET with the resource, as a server does. rust/src/offline/wire.rs `a_listed_body_that_is_not_utf8_crosses_as_its_bytes`: `no body: None`. JVM, over the real engine and store: `aCardThatIsNotUtf8IsStoredAsServedAndNeverWrittenBackAsText`, `anEntryThatIsNotUtf8IsStoredAsServedAndNeverWrittenBackAsText` (the stored bytes and hash are the served ones, an edit throws and nothing reaches the server); these need the new record component and could not run on the old code.

Tests changed because they stated the old identity: CalendarMirrorTest `anEventCreatedInACalendarAppJoinsTheStore` (the body is filed under the entry's `UID`, not the phone's handle), EventWritesTest `followingStagesTheNewSeriesThenTheShortenedOne` (`add ... s-2` and the series stored under `s-2`, not `s-2.ics`), CalendarDavWriteTest's first test stages `e-1`; ContactIndexTest, ContactMergeTest, ContactClientTest, CalendarClientTest and MailClientTest `mailboxesReadAsBefore` gained the new reply fields (`linkId`, `vcardBase64`, `icalBase64`, `label`).

## Evidence

From the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.16.0's spec/, on 2026-11-18:

- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 693 passed, 1 ignored (686 before, 7 new).
- `gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain`: 1,027 tests (1,022 before, 5 new), 5 skipped, 2 failing, both tests still stating the old shapes (MailClientTest `mailboxesReadAsBefore`, the mirror of `Mailbox` without `label`; EventWritesTest `followingStagesTheNewSeriesThenTheShortenedOne`, reading the series under `s-2.ics`); after correcting them, both classes re-run green and `:app:assembleDebug` and `:app:assembleRelease` (a JNI-read reply shape moved) build. SyncVectorsTest passes with the ledger unchanged: 167 cases, 126 pass, 10 fail, 31 unbound, no vector moving.
- `cairn/verify.sh`: conformant.
- Re-run whole after the two test corrections, the same day: `cargo test --locked` 693 passed, 1 ignored; `gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`: 1,027 tests, 5 skipped, none failing.

Nothing was installed on a device and nothing connected to an account.

## Left

- io-webdav decodes a whole multistatus as UTF-8 before parsing (`body_text`), so a body that is not UTF-8 reaches the app lossy and is read again by GET, one request per such body. The library could hand `address-data` and `calendar-data` as the bytes the answer held (and honour an XML `encoding` declaration); recorded for phase X.
- A copy of such a card made in the app (the editor's add to another book) stages the lossy text as a new body; its push is not refused, since that body is well-formed UTF-8 holding U+FFFD. Rare; a guard on U+FFFD in a new body would also refuse a card that legitimately holds the character.
- Stores synced before keep their resource-name keys for bound members until the phase V rebuild.
- Cost, not measured: a phone pass reads each card's store row once more (`PhoneRemote.servedBase`), and `PimdirStorage.wellFormed` reads a body's blob a second time beside `bodyOf`; to check on a large book in the device pass (task G1).
- The UI says nothing more than the refusal's message when an edit of such a body is refused (a UX step of its own, as for every refusal).

## Capabilities moved

- offline-store: added "An item's link id follows STORAGE §9", "A DAV body is stored as served".
- carddav-sync: added "A card is keyed by its UID as STORAGE §9 assigns", "A card that is not UTF-8 is kept as served".
- calendar: added "An entry is keyed by its UID", "An entry that is not UTF-8 is kept as served".
- mail: added "A folder's key escapes its own name", "A JMAP message is named from its header bytes"; modified the date requirement (JMAP's date is its `Date` header) and the round requirement (JMAP named from the raw `headers`).
