---
cairn: delta
change: sync-vectors-run-through-the-java-store
---

Folds into spec/offline-store.md.

## ADDED Requirements

### Requirement: The sync vectors run through the app's load and write
The app SHALL run every case of the sync vectors of the io-pimdir it pins (pimdir SYNC §11) in a host test, on a store of its own over Android 8.0's SQLite: the case's rows seeded by the standard's own statements and plain SQL, never through the write under test; its run driven by io-pimdir's engine through the coroutines the bridge's sync, upgrade and mutate exports build, every load, lookup, verification and write served by the app's storage half and the remote scripted from the case on the wire a connector answers; its pushes, events, failure, park and rows compared as §11 says. A case the app does not pass SHALL be listed beside the test with its reason, as `fails` when the app runs it and departs from it, as `unbound` when the bridge states no such verb, option or mutation or the case binds another kind of owner, and the test SHALL fail when a listed case passes, an unlisted one fails, a case listed unbound binds, an unlisted one cannot be bound, or a listed one is not among the vectors. At io-pimdir cd33f0e, 158 cases: 117 pass, 11 fail, 30 are unbound. While a case is listed `fails`, the app's `sync-draft-11 engine` statement stands short of SYNC §12 by it.

#### Scenario: A rule the Java write lacks
- GIVEN a vector whose expected rows the Java write does not leave
- WHEN the host tests run
- THEN the suite fails naming the case and each row that differs, unless the case is listed with its reason

#### Scenario: A listed case that starts passing
- GIVEN a case listed `fails`
- WHEN a change of the load or the write makes it pass
- THEN the suite fails until the line is taken off the list

#### Scenario: A pin bringing a new case
- GIVEN an io-pimdir whose vectors hold a case the list does not name
- WHEN the host tests run
- THEN the case runs, and the suite fails if it fails or cannot be bound

### Requirement: A load states the copies it offers
A store load SHALL name, as `offered`, each placement it projects as a create for an item the source does not bind: a copy the store offers it of an item another source holds (pimdir SYNC §3, §9). Every other create is the source's own, pending or delivered, which alone the engine reports a member minted apart from (`Disagreed`, SYNC §6).

#### Scenario: A message another source holds
- GIVEN a message one source binds, and a second source listing another message under its `Message-ID`
- WHEN the second source syncs
- THEN the member is filed under a key of its own and reported added, and no create of the second source is reported disagreeing with it

### Requirement: A page closes its listing only when it says so
The bridge SHALL hand the engine a listed page as its connector states it: a page saying it is not the last while handing no cursor SHALL NOT be read as the last. The engine refuses it before its write, the round left open on the cursor the page before landed, where closing on it would have dropped every member no page reached (pimdir SYNC §4, §5). A page stating neither closes its listing, as a connector that does not page answers.

#### Scenario: A page that neither ends nor resumes
- GIVEN a round whose first page landed with a cursor, and a second page stating `last` false and no cursor
- WHEN the second page is listed
- THEN the sync fails, the round stays open on the first page's cursor, and no member is dropped
