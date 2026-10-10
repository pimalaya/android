---
cairn: delta
change: sync-strip-progress
---

Folds into `spec/offline-store.md`.

## ADDED Requirements

## MODIFIED Requirements

### Requirement: A sync says what it is working on, in every domain
A running sync SHALL show, under the large title of every list, a strip naming what the pass is on and what it is doing, over a thin bar: the account on a first line once the pass reaches one (*me@example.org*), the step it stands at on the line below (*Mailboxes synced: 3 of 12*). The three domains SHALL report alike, so a wait reads the same whichever one is being synced. A counted step SHALL count what the domain holds: messages for mail, events for calendars, contacts for the books, singular or plural as the count asks; the steps against the phone SHALL be the two mirrors', contacts and calendars.

The bar SHALL fill only from what is counted. It SHALL fill as an account's collections land, its mailboxes, address books or calendars (*Calendars synced: 2 of 5*); while a step moves items batch by batch, the members a listing did not carry being read or the contacts and events being written to the phone, it SHALL measure that step, a calendar's only while that calendar runs alone. Between counts it SHALL hold what the account's collections came to, and it SHALL run indeterminate only before anything is counted.

While the strip shows, the list's search field and its chips SHALL be hidden, and SHALL come back when it goes; a search field holding a query SHALL stay.

The strip SHALL NOT block the app: the lists, the reader and the composer stay usable while it runs, the lists refreshing when the pass ends. One pass SHALL run at a time, a pull or the drawer's sync asked for meanwhile doing nothing, and an account SHALL NOT be deleted while a pass runs. The strip's line SHALL never be empty: it SHALL open on a step saying it is preparing, and SHALL follow the pass as it moves to the next account and as it steps.

#### Scenario: A pass over every domain
- GIVEN the drawer's sync
- WHEN it moves from contacts to mail to calendars
- THEN the strip's step speaks of contacts, then of mail, then of events

#### Scenario: A pass over several accounts
- GIVEN two mail accounts
- WHEN the mail pass moves from the first to the second
- THEN the strip's first line names the first account then the second

#### Scenario: The roster round
- GIVEN a pass that has to list an account's mailboxes or calendars first
- WHEN it starts
- THEN the step is set before that round, never an empty line

#### Scenario: The first frame
- GIVEN a sync the user has just asked for
- WHEN the strip shows, before any round trip
- THEN its step says it is preparing

#### Scenario: The agenda's bodies
- GIVEN a calendar pass reading 23 entries
- WHEN the strip steps to the download
- THEN its step reads *Downloading 23 events*, never contacts

#### Scenario: A book's first download
- GIVEN a CardDAV book of 230 contacts synced for the first time
- WHEN 128 of them have been read
- THEN the step reads *Downloading 230 contacts* over a bar a little more than half full

#### Scenario: Calendars side by side
- GIVEN an account of five calendars, two of them synced
- WHEN the others check the server or download at once
- THEN the bar stands at two fifths, whatever the step line says

#### Scenario: The controls step aside
- GIVEN the mail list with an empty search field and its chips
- WHEN a pass starts
- THEN the strip shows where the search field and the chips were
- AND both come back once the pass ends

#### Scenario: Reading while it runs
- GIVEN a sync running
- WHEN a message is opened, or a sync pulled for
- THEN the message opens, and no second pass starts

## REMOVED Requirements
