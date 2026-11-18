---
cairn: delta
change: the-app-follows-neverest-v1
---

# Delta

Each requirement names the spec file it folds into and the task that lands it. The wording is refined when a task lands, to say what was built; a task closed without a change (S8 if its test passes, L4) drops its requirement.

## ADDED Requirements

### Requirement: An item's link id follows STORAGE §9 (offline-store, carddav-sync, calendar; I1)
A card's, an entry's and a task's link id SHALL be the key STORAGE §9 assigns from its hint: the `UID` verbatim when usable, `esc:` before a hint beginning with a reserved prefix, the `hash:` fallback for none, a second resource under one key minted `dup:`. (As decided by the owner, Q1; a stated departure replaces this requirement if they decide otherwise.)

#### Scenario: A UID that looks like a derived key
- GIVEN a server card whose `UID` is `hash:0123`
- WHEN it is listed
- THEN its link id is `esc:hash:0123`

### Requirement: A Graph folder's key escapes its own name (mail; I2)
A Graph mail folder's collection key SHALL be its path of names, each name with `%` written `%25` and `/` written `%2F`, so two folders never share a key.

#### Scenario: A slash in a name
- GIVEN a top-level folder `A/B` and a folder `B` under `A`
- WHEN the roster is listed
- THEN they are two collections

### Requirement: A JMAP message is named from its header bytes (mail; I3)
A JMAP message's summary and link id SHALL be derived by `derive_meta` from its raw header fields, as an IMAP, Gmail or Graph message's are, never from JMAP's parsed properties.

### Requirement: A DAV body is stored as served (offline-store, carddav-sync, calendar; I4)
A CardDAV or CalDAV body SHALL be stored and hashed as the bytes the server sent. A phone edit of a body that is not UTF-8 SHALL NOT be pushed; it is refused and logged. (As decided by the owner, Q2.)

### Requirement: A copy of a body that is not UTF-8 is refused (carddav-sync; D6)
A copy made in the app of a card whose stored body is not UTF-8 (the editor ticking it into another book) SHALL be refused as its edit is, nothing staged. (Owner decision Q2; folded into "A card that is not UTF-8 is kept as served".)

### Requirement: A send that may have gone is not sent again (mail; D1)
An HTTP submission (Gmail, Graph, JMAP) that failed after its request was sent, with no verdict read, a 2xx whose body did not parse included, SHALL be parked as uncertain. Only a request that could not be built stays queued.

#### Scenario: A 200 that does not parse
- GIVEN Gmail answers a send 200 with a body that is not JSON
- WHEN the drain ends
- THEN the message is parked as uncertain and the next drain does not send it

### Requirement: A Google contact update keeps what the card cannot hold (carddav-sync; D2)
A Google People update SHALL be the projection merged over the current person (`GpeoplePerson::merge_unprojected`), so a field the vCard cannot show (a second organization, an HTML biography) survives an edit of another field.

### Requirement: A series is created whole (calendar; D3)
A series created on Graph or Google SHALL carry its exclusions and overrides: the master, then each occurrence, then the attendees, so invitees get one invitation. If an occurrence cannot be written the master SHALL be deleted and the push rejected. An override matching no instance, and a `RANGE=THISANDFUTURE` override, SHALL be refused.

### Requirement: A multiget row not answered as gone keeps its member (carddav-sync, calendar; D4)
In a DAV multiget, only a 404 or 410 row SHALL mean the member is gone; any other unreadable row SHALL be read alone, and if still unreadable SHALL fail the round rather than retire the member.

### Requirement: An IMAP session out of step is reopened (mail; D5)
An IMAP batch error that leaves part of an answer unread SHALL reset the session so the next command reopens it.

### Requirement: QRESYNC is what the server enabled (mail; S1)
The app SHALL send a QRESYNC `SELECT` only on a connection whose `ENABLED` answer named QRESYNC.

#### Scenario: iCloud
- GIVEN a server advertising QRESYNC that answers `ENABLE` with a bare `OK`
- WHEN a mailbox is listed again
- THEN the listing is the full round, and a message arrived since is listed

### Requirement: Gmail's Sent and Drafts are never modified (mail; S3)
A move into or out of Gmail's SENT or DRAFT, but a move out into the trash, and a copy into either SHALL be refused before any request; a removal from either SHALL go to the trash. A copy out of either names neither and is carried out.

### Requirement: A contact or calendar push refused as it stands is held (carddav-sync, calendar, tasks; S4)
A contact, entry or task push the server refuses as it stands (a status naming the request) SHALL be held as refused on its binding and reported, as a mail push is, and the rest of the collection SHALL be pushed.

### Requirement: A floating entry is anchored for Google and Graph (calendar; S5)
An entry with floating times pushed to Google or Graph SHALL be anchored in the phone's zone, its `UNTIL` written in UTC.

### Requirement: A DAV listing is bounded (carddav-sync, calendar; S6)
A `sync-collection` drain SHALL stop after a bounded number of rounds or on a truncation with no new token, and SHALL answer an incomplete round, which retires nothing.

### Requirement: A sending limit keeps the message queued (mail; S9)
An SMTP refusal naming a sending limit SHALL leave the message queued.

### Requirement: Every Graph contact folder is a book (carddav-sync; L2)
A Graph account's books SHALL be every contact folder at every depth, each keyed by its folder's id.

### Requirement: A typed host that is not ASCII asks for its Punycode form (onboarding; L8)
A typed server whose host is not ASCII SHALL be refused on its card, naming the host and asking for its Punycode form. (Owner decision Q4; folded into "Setup accepts encrypted servers only".)

## MODIFIED Requirements

### Requirement: An HTTP sync rides out throttling (mail; L1)
A throttling wait SHALL be cut short when the app stops the work (the background job told to stop, every thread's waits; the app closing, the interrupted thread's), the throttled answer failing the request at once.

### Requirement: A row Graph lists no header field for keeps its subject (mail; L3)
The `subject` of a Graph row listing no header field SHALL be taken as Graph serves it, never decoded again.

### Requirement: Expansion is bounded whatever the rule (calendar; L7)
Graph's five-year window for a series' exceptions SHALL be stated, with what an exception outside it becomes. (Owner decision Q5.)

### Requirement: A copy of a body that is not UTF-8 is refused (carddav-sync, tasks; L9)
A merge reading a card that is not UTF-8, and a to-do that is not UTF-8 filed into another list, SHALL be refused as a copy is, nothing staged.

### Requirement: An IMAP session out of step is opened again (mail; L10)
Every IMAP command failing with its answer part unread, or its connection failing under it, SHALL leave the session out of step, the next command opening the connection again, signed in.

### Requirement: The sync vectors run through the app's load and write (offline-store; B4, V5)
The counts line SHALL name the io-pimdir the app pins and the counts its run gave (at io-pimdir 0.16: expected 126 pass, 10 fail, 31 unbound, case 58 passing).

### Requirement: A band lists from its floor (mail; S2)
A Graph or JMAP band's first page SHALL be bounded by its floor alone, never by a ceiling on the provider's date, so a message whose `Date` and provider date differ is listed by the band its `Date` falls in.

### Requirement: A Google delete of an entry already gone is done (calendar; S7)
A Google or Graph event delete read or answered 404 or 410, or reading a cancelled master, SHALL settle the removal.

### Requirement: A store of another draft is not opened (offline-store; V4)
Restated at pimdir v1: a draft store and a store naming a later version are refused, the conformance statement naming storage-v1 and sync-v1, and the index version ordered as SEARCH.md §1 says.

## REMOVED Requirements
