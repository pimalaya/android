---
cairn: delta
change: sync-strip-progress
---

Folds into `spec/offline-store.md`.

## ADDED Requirements

### Requirement: A sync that went through says nothing
A sync that went through SHALL NOT pop up a report: no count of what came in or went out, no count of messages sent from the outbox, no count of conflicts left, the lists showing what the pass brought and a conflicted row carrying its own mark. A pull turned down because a background run holds the sync SHALL say nothing either, that run's strip already showing. A failure SHALL still open its error dialog.

#### Scenario: A quiet pass
- GIVEN a contacts pass bringing three cards and leaving one conflict
- WHEN it ends
- THEN no toast shows, and the conflicted contact carries its mark in the list

### Requirement: The mail list shows its background download
While the background fill downloads older mail or bodies, the mail list SHALL show a download glyph beside its count, pulsing gently in opacity, and holding still when the system removes animations; a long press SHALL name it (*Loading older mail*). It SHALL show only once a step downloaded something, and SHALL go when the fill ends or pauses (the app left, a metered network, no network).

#### Scenario: After the first mail sync
- GIVEN a first mail sync that landed the newest messages of each mailbox
- WHEN the fill widens them in the background
- THEN the glyph pulses beside the count, and goes once the fill stops

## MODIFIED Requirements

### Requirement: A sync says what it is working on, in every domain
A running sync SHALL show, under the large title of every list, a strip naming what the pass is on over one thin bar: the account on a first line once the pass reaches one (*me@example.org*), the domain on the line below (*Syncing contacts*), never the engine's steps. The three domains SHALL report alike, so a wait reads the same whichever one is being synced.

The bar SHALL be one progress over the whole pass, every account and every domain it runs, and SHALL never move back while the pass runs. Before it starts, the pass SHALL be sized in sections, one per domain and account, each weighing the collections stored for it, one at least; a section SHALL fill its weight however many collections its listing turns up, and sections MAY fill at different speeds. Within a section, the pass SHALL tell how many collections it will run before the first one runs, and each collection SHALL fill its share as it lands; within it, a step moving items batch by batch, the members a listing did not carry being read or the contacts and events being written to the phone, SHALL fill part of that share, the download the first three fifths and the phone the rest where the domain has a phone mirror, the download all of it for mail; a calendar's steps only while that calendar runs alone. The bar SHALL run indeterminate only before anything is counted.

While the strip shows, the list's search field and its chips SHALL be hidden, and SHALL come back when it goes; a search field holding a query SHALL stay.

The strip SHALL NOT block the app: the lists, the reader and the composer stay usable while it runs, the lists refreshing when the pass ends. One pass SHALL run at a time, a pull or the drawer's sync asked for meanwhile doing nothing, and an account SHALL NOT be deleted while a pass runs. The strip's line SHALL never be empty: it SHALL open saying it is preparing, and SHALL follow the pass to the next account and domain.

#### Scenario: A pass over every domain
- GIVEN the drawer's sync
- WHEN it moves from contacts to mail to calendars
- THEN the strip's line reads *Syncing contacts*, then *Syncing mail*, then *Syncing calendars*
- AND one bar fills once across the three

#### Scenario: A pass over several accounts
- GIVEN two mail accounts
- WHEN the mail pass moves from the first to the second
- THEN the strip's first line names the first account then the second, and the bar carries on from where the first left it

#### Scenario: The first frame
- GIVEN a sync the user has just asked for
- WHEN the strip shows, before any round trip
- THEN its line says it is preparing

#### Scenario: A book's first download
- GIVEN an account of one CardDAV book of 230 contacts, mirrored on the phone, synced for the first time
- WHEN 115 of them have been read
- THEN the bar stands at three tenths
- AND writing them to the phone carries it on from three fifths to full, never back to empty

#### Scenario: Calendars side by side
- GIVEN an account of five calendars, two of them synced
- WHEN the others check the server or download at once
- THEN the bar stands at two fifths at least

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
