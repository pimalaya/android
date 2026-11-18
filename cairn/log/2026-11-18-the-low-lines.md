---
cairn: log
change: the-app-follows-neverest-v1
date: 2026-11-18
---

# The low lines (phase L)

Phase L of cairn/changes/the-app-follows-neverest-v1: the low tasks. Built on phases B, I, D and S (a8359b5, 4dd2524), uncommitted in the same tree. L5 and L6, optional, were not asked and are left open.

## L1. A stop cuts a throttle wait

The throttle (rust/src/client/throttle.rs) and the two body readers that pause (rust/src/mail/gmail.rs `read_all`, rust/src/mail/msgraph.rs the Graph `$batch` read) waited with `thread::sleep`, which no stop reaches: a background run told to stop by JobScheduler sat out up to 30 s (75 s for a minute quota) per request, then sent it again. `Host::sleep` now answers whether the wait ran whole (rust/src/client.rs), and the JNI host waits on the Java side, `Transport.pause(long)` (android/client Transport.java; rust/src/ffi/host.rs, which falls back to `thread::sleep` with no transport or an upcall failing). `pause` waits on one process-wide monitor and answers false, the wait cut, when the calling thread is interrupted (the app closing: `MainActivity` `shutdownNow` interrupts its executors), the interrupt kept for the caller, or when `Transport.stopWaits` was called, which wakes every waiting thread, pool workers included, until `Transport.allowWaits`. java/BackgroundJob.java `onStopJob` calls `stopWaits`, beside the flag asked between domains, and the job's thread calls `allowWaits` once its run ends. A stop does not interrupt the background thread: an interrupt would reach the CardDAV push pool's `Future.get` (java/contact/ContactEngine.java `pushAll`), which would drop the outcomes of pushes the server took, and the worker threads of a pool are not the job's thread anyway. A wait cut short hands the throttled answer back at once, as a wait past the budget does, so the coroutine fails with the server's status (a 429 stays transient, never uncertain); a Gmail pacing wait cut short fails before its request, the exchange reset so a send it paced is not told sent (`Client::pace_gmail`); a body reader returns what it read. android/client/consumer-rules.pro keeps `boolean pause(long)`.

## L2. Every Graph contact folder is a book

rust/src/contact/msgraph.rs `list_addressbooks` listed `/me/contactFolders` alone. It now walks every depth, each level after the one above it, `childFolders` of every folder (`MsgraphContactChildFoldersList`, io-msgraph 0.9.3), pages followed (`contact_folder_pages`), as neverest does (src/msgraph/client/contacts.rs). The task's "same key escaping as I2" does not apply: a contact book is keyed by its folder's id (rust/src/contact.rs builds `{base}/{id}`), never by its name, so two folders of one name are two books already. A child is named by its own display name, as neverest names it.

## L3. A Graph subject is not decoded twice

rust/src/mail/msgraph.rs `graph_summary`, for a row listing no header field, ran io-pimdir's `decode` over Graph's `subject`, which Graph decoded already: a subject literally holding `=?utf-8?q?x?=` read as `x`. It is now taken as served, its ends trimmed of spaces and tabs as Annex A's decoding trims them.

## L4. The unknown-flags write (closed without code)

The premise does not hold: the engine never hands the app's store an unknown flag set over a row holding a known one. Read on 2026-11-18 at io-pimdir v0.16.0:

- A listed member always states its markers: the wire's `RemoteItemJson.flags` is a `Vec<String>` defaulting to empty (rust/src/offline/wire.rs), read as `PimdirFlags::from_iter`, never `Unknown`; every connector names a member with its flags (IMAP `FLAGS`, Gmail's labels, Graph's `isRead` and `flag`, JMAP's keywords, a DAV member none, which is the known empty set). So a placement the engine names from a listing (io-pimdir src/sync.rs `named`, `landed`) or merges with one (`flag_delta` over the listed set) carries known flags.
- Every other upsert carries the flags the engine loaded, and the load reads them off the item (rust/pimdir/queries/app/load_placements.sql, `NULL` only when `flags_known` is 0): unknown only over a row already holding an unknown set.
- io-pimdir's hub keeps a known set against an unknown one (src/hub.rs `absorb_upsert`, "an unknown set erases nothing") because its reference owner folds every write into the hub it read; the app writes the placement it is handed (java/pimdir/PimdirStorage.java `applyUpsert`, java/pimdir/PimdirItems.java `writeFlags`), which matches whenever the placement's flags are those the row held.

The window left is a write landing on the row between an engine load and its write while the row's set is unknown, which no path makes: mail rows are known from their first listing, and no UI writes markers on a contact or an entry. No vector states the case (spec/vectors/sync, every remote snapshot stating its flags). No change, no requirement.

## L7. Graph's five-year series window

cairn/spec/calendar.md "Expansion is bounded whatever the rule" states the window as kept by the owner (Q5): exceptions read within five years either side of today, in windows of at most five years (Graph answers 400 past that), none for a series ended before, and an exception outside the window not read, the entry showing the rule's occurrence there; a scenario. No code (rust/src/calendar/msgraph.rs `series_windows`, `SERIES_YEARS`, `MAX_WINDOW_YEARS` 5).

## L8. A typed host that is not ASCII asks for its Punycode form

The premise "the app passes it on and DNS fails" was false for the onboarding's server fields: java.net.URI reads no host in `imaps://bücher.de:993` or `https://dav.bücher.de/` (checked with the JDK's URI, which Android's libcore shares), so java/account/Endpoints.java `manualUrl` and `submitUrl` answered null and the card said "Only encrypted servers are accepted", the wrong reason. As decided (Q4), java/setup/OnboardingFlow.java `formProblem` now asks `Endpoints.unicodeHost` first (the host of a bare host, a host and port, or a URL with userinfo, when it is not ASCII) and says `server_not_ascii`, naming the host and asking for its Punycode form (`xn--…`) as neverest's message does (src/server.rs `ascii_host`), in English and French. The check stays in Java: no bridge call validates these fields, and the app carries no IDNA conversion to suggest the form with (java.net.IDN is IDNA2003, which spells some names otherwise than UTS 46). Discovery is not touched: a discovered https host goes through url's IDNA, and the OAuth URL fields are https too.

## L9. A move or a merge of a body that is not UTF-8 is refused

- java/task/TaskWrites.java `move` (the clarify flow filing a to-do in another list, `moveTo` and `mergeMove`) staged the to-do's text, U+FFFD where its bytes were, as an add in the target. It now calls `Stager.requireText` on the source inside the move's transaction, before anything is staged.
- java/contact/ContactWriter.java `saveMerge` wrote the survivor from the merge form's model, which may carry a replica's lossy text, and staged the others' deletes; the survivor's own `requireText` passed when it was UTF-8. It now asks java/contact/PimdirContacts.java `requireText` (the loop `copy` ran, made a method of its own) for every replica first, so nothing is staged on any card. `mergeDirect` (identical cards, deletes only, no text written) is unchanged.

## L10. Every IMAP command out of step opens the connection again

Phase D reopened the session after a streamed body batch alone. rust/src/mail/imap.rs `ImapSession::attempt`, which every other command goes through (`run`, `write`, and the EXAMINE, SELECT and header FETCH that read the server's refusal), now marks the session out of step when the coroutine fails before its answer was read to its end (a new `AnswerFailure::left_unread`: anything but a tagged `NO` or `BAD`, an `OK` that named nothing, or a send failing on its tag before writing; a `BYE`, an end of stream, an undecodable line, a response past its bound count), and when the wire fails under the command (Java's transport then drops the connection, and the next write went out on a new one, greeted and not signed in). The next command opens the connection again first (`reopen`: dropped, a fresh `ImapState`, signed in with the session's credential); `reopen` keeps the session in step while it signs in and marks it out of step again when that fails, so a failure during the sign-in cannot loop. A session bound without a credential fails its next command, as phase D's did, until a signed bind. The streamed batch keeps its own reading.

## Tests

Each seen failing on the code before its fix, then passing:

- L1, android/app/src/test/java/org/pimalaya/client/ThrottleStopTest.java `anInterruptCutsAThrottledWaitShort`, through the real bridge and Java transport: a CardDAV server on the loopback, TLS under a certificate made for the run (keytool) and trusted for it alone (Robolectric's Conscrypt off), answers every request 503 `Retry-After: 20`, the calling thread interrupted a second in: `waited 20222 ms of a 20 s wait`. Now cut at the interrupt, the 503 failing the call, no request sent twice (the discovery's next address is given up at once too) and the interrupt kept. `aStoppedJobCutsAThrottledWaitShort`, the same over `Transport.stopWaits`, then a wait running whole after `allowWaits`, was written with the fix (`stopWaits` is new). rust/src/client/throttle.rs `a_stop_cuts_a_throttled_wait_short` and `a_stop_cuts_a_pacing_wait_short_unsent` were written with the fix: `Host::sleep` changed its signature, so they cannot build on the old code.
- L2, rust/src/contact/msgraph.rs `a_contact_folder_below_the_top_level_is_listed` over a fake Graph holding Family, Kids and School under it, and Cousins on a second page of Family's children: `left: [("", "Contacts"), ("F", "Family")]`.
- L3, rust/src/mail/msgraph/tests.rs `a_subject_graph_decoded_is_not_decoded_again`: `left: "Write x to encode" right: "Write =?utf-8?q?x?= to encode"`.
- L8, android/app/src/test/java/org/pimalaya/account/EndpointsTest.java `aTypedHostThatIsNotAsciiIsNamedForItsPunycodeForm`: its first three lines, the old refusal through `manualUrl` and `submitUrl`, pass on the old code (the premise); `Endpoints.unicodeHost` is new, so the rest failed to compile there (`cannot find symbol: method unicodeHost(String)`). The card's message itself has no JVM harness (OnboardingFlow drives views): see Left.
- L9, android/app/src/test/java/org/pimalaya/task/TaskSyncTest.java `aToDoThatIsNotUtf8IsNotMovedToAnotherList`, over the CalDAV fake serving the to-do as Latin-1 bytes (a `served` map, as CalendarDavWriteTest's): `expected org.pimalaya.client.PimalayaException to be thrown, but nothing was thrown`; android/app/src/test/java/org/pimalaya/contact/ContactEngineDavCreateTest.java `aMergeOfACardThatIsNotUtf8IsRefused`, through the real editor's merge form and save: `expected:<[]> but was:<[c2.vcf]>`, the UTF-8 survivor updated.
- L10, rust/src/mail/imap/tests.rs over the scripted server, which now answers `UID COPY` and `APPEND` (its literal), opens a line no client can decode before a command's answer (`broken`), loses the connection after one (`dies_after`, the client's reset then greeting a connection not signed in) and answers `NO` to any command before `AUTHENTICATE` on such a connection: `a_list_left_part_unread_opens_the_connection_again` (`left: (["INBOX", "Archive", "INBOX", "Archive"], 0)`, the second roster reading the first's lines), `a_listing_left_part_unread_opens_the_connection_again` for EXAMINE, `UID SEARCH` and the header `UID FETCH` (`left: [("EXAMINE", Ok(3), 0), ("UID SEARCH", Ok(3), 0), ("UID FETCH", Ok(3), 0)]`, never opened again: io-imap skips another command's tagged line, so these leftovers happened to read harmless), `a_write_whose_connection_died_opens_it_again` for `UID STORE`, `UID COPY` and `APPEND` (`left: [("UID STORE", Err("IMAP STORE failed: NO not signed in"), 0), ("UID COPY", Err("IMAP COPY failed: NO not signed in"), 0), ("APPEND", Err("IMAP APPEND failed: NO not signed in"), 0)]`).

## Evidence

From the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.16.0's spec/, on 2026-11-18:

- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 737 passed, 1 ignored (730 before, 7 new: L1 2, L2 1, L3 1, L10 3).
- `gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain`: 1,036 tests (1,031 before, 5 new: ThrottleStopTest 2, EndpointsTest 1, TaskSyncTest 1, ContactEngineDavCreateTest 1), 5 skipped, none failing, the debug and release builds assembled (`Transport.pause` is called from native code, kept in android/client/consumer-rules.pro). SyncVectorsTest: 167 cases, 126 pass, 10 fail, 31 unbound, the ledger unchanged and no vector moving.
- `cairn/verify.sh`: conformant.

Nothing was installed on a device and nothing connected to an account.

## Left

- A stop the system sends after the run's `finally` called `Transport.allowWaits()` and before `jobFinished` leaves the waits cut until the next background run ends: until then an interactive sync meeting a throttling server fails at once as transient rather than waiting. Nothing is lost; a fix (waits allowed again at the start of every run and of every interactive sync) needs its own failing test.
- L5 (cursor paging in the app's search fork) and L6 (`move-in-place` mapped like `body-replaced`): optional, not asked, left open.
- The device checks, for the owner: on the OnboardingFlow advanced setup, typing `bücher.de` (mail), `https://dav.bücher.de/` (contacts or calendars) and `smtp.bücher.de` (sending) shows the Punycode message on the card, in English and in French; a background run stopped by the system while a server throttles ends at once (a debug build logs "wait cut short").
- The wait stop is process-wide: a job stopped while the user opens the app cuts the app's throttled waits too until the job's run ends (the job holds the sync lock meanwhile, so no interactive sync runs then); a new job instance ending first clears it early, its older run's waits then running whole as before. java/BackgroundJob.java's `stopped` flag is never reset when the system reuses the service instance, as before this change.
- A stopped background run still lists and pushes between its waits: only the waits are cut, the domains checking `stopped` between them as before.
- An IMAP write whose connection died after the server took it is told failed and staged again; the next push meets what the server did (a STORE again is harmless, a COPY or an APPEND finds the Message-ID, as phase S's guards do).

## Capabilities moved

- mail: modified "An HTTP sync rides out throttling" (a stop cuts a wait, a scenario), "An IMAP session out of step is opened again" (every command, two scenarios), and the Graph header-field requirement (the subject of a row listing none taken as served).
- carddav-sync: added "Every Graph contact folder is a book"; modified "A card that is not UTF-8 is kept as served" (a merge refused, a scenario).
- tasks: modified "Filing a capture elsewhere never loses it" (a to-do not UTF-8 is not filed elsewhere, a scenario).
- calendar: modified "Expansion is bounded whatever the rule" (Graph's five-year window stated, a scenario).
- onboarding: modified "Setup accepts encrypted servers only" (a host that is not ASCII, a scenario).
