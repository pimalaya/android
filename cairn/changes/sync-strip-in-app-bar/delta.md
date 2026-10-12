---
cairn: delta
change: sync-strip-in-app-bar
---

Folds into `spec/offline-store.md` (the strip) and `spec/mail.md` (the background download).

## ADDED Requirements

## MODIFIED Requirements

### Requirement: A sync says what it is working on, in every domain
A running sync SHALL show, as an extension of the app bar of every list (mail, contacts, calendars), so it shows wherever the list is scrolled, a strip naming what the pass is on: one line in the bar's lower part under its title, the domain (*Syncing contacts*) followed by the account once the pass reaches one (*Syncing contacts · me@example.org*), never the engine's steps, over one thin bar on the app bar's bottom edge. The strip SHALL be drawn within the app bar's height: showing, updating or hiding it SHALL change neither the bar's height nor its title's position nor the list's. The three domains SHALL report alike, so a wait reads the same whichever one is being synced. The strip SHALL hide when nothing runs, the app bar then as it is without it, and SHALL not show on screens other than the lists.

The bar SHALL be one progress over the whole pass, every account and every domain it runs, and SHALL never move back while the pass runs. Before it starts, the pass SHALL be sized in sections, one per domain and account, each weighing the collections stored for it, one at least, an account's mail step weighing one; a section SHALL fill its weight however many collections its listing turns up, and sections MAY fill at different speeds. Within a section, the pass SHALL tell how many collections it will run before the first one runs, and each collection SHALL fill its share as it lands; within it, a step moving items batch by batch, the members a listing did not carry being read or the contacts and events being written to the phone, SHALL fill part of that share, the download the first three fifths and the phone the rest where the domain has a phone mirror, the download all of it for mail; a calendar's steps only while that calendar runs alone; a mail step its bodies (A mail step counts the bodies it brings). The bar SHALL run indeterminate only before anything is counted, its line then saying the sync is starting (*Starting the sync*) rather than naming a domain, and SHALL keep the determinate bar's track and height.

The list's search field, its chips and its count line SHALL stay while the strip shows.

The strip SHALL NOT block the app: the lists, the reader and the composer stay usable while it runs, the lists refreshing when the pass ends. One pass SHALL run at a time, a pull or the drawer's sync asked for meanwhile doing nothing, and an account SHALL NOT be deleted while a pass runs. The strip's line SHALL never be empty: it SHALL open saying the sync is starting, and SHALL follow the pass to the next account and domain once something is counted.

#### Scenario: A pass over every domain
- GIVEN the drawer's sync
- WHEN it moves from contacts to mail to calendars
- THEN the strip's line reads *Syncing contacts*, then *Syncing mail*, then *Syncing calendars*
- AND one bar fills once across the three

#### Scenario: A pass over several accounts
- GIVEN two mail accounts
- WHEN the mail pass moves from the first to the second
- THEN the strip's line names the first account then the second, and the bar carries on from where the first left it

#### Scenario: The first frame
- GIVEN a sync the user has just asked for
- WHEN the strip shows, before any round trip
- THEN its line says the sync is starting, over an indeterminate bar the size of the counting one

#### Scenario: A book's first download
- GIVEN an account of one CardDAV book of 230 contacts, mirrored on the phone, synced for the first time
- WHEN 115 of them have been read
- THEN the bar stands at three tenths
- AND writing them to the phone carries it on from three fifths to full, never back to empty

#### Scenario: Calendars side by side
- GIVEN an account of five calendars, two of them synced
- WHEN the others check the server or download at once
- THEN the bar stands at two fifths at least

#### Scenario: Scrolled to the end
- GIVEN the mail list scrolled to its last message
- WHEN *Load more* is tapped
- THEN the strip shows in the app bar, its bar filling, without scrolling back up

#### Scenario: Nothing moves
- GIVEN the mail list at its top, its large title, search field and chips shown
- WHEN a pass starts, counts and ends
- THEN the strip shows in the app bar and goes, the large title, the search field, the chips and the rows never moving

#### Scenario: Reading while it runs
- GIVEN a sync running
- WHEN a message is opened, or a sync pulled for
- THEN the message opens, and no second pass starts

### Requirement: The mail list shows its background download
While the background fill downloads older mail or bodies and no pass runs, the mail list's strip in the app bar SHALL read *Loading older mail* beside a download glyph pulsing gently in opacity, holding still when the system removes animations, a long press on the glyph naming it. While bodies download it SHALL show a bar of the bodies landed out of the run's plan, every account's summed, a body counting once its file is written and a replan adding to the plan; the header fill alone has no bar. The bar SHALL never move back while the fill runs. The strip SHALL show only once a step downloaded something, and SHALL go when the fill ends or pauses (the app left, no Wi-Fi).

#### Scenario: After the first mail sync
- GIVEN a first mail sync that landed the newest messages of an IMAP account
- WHEN the fill widens its mailboxes in the background
- THEN the strip pulses its glyph in the app bar, and goes once the fill stops

#### Scenario: Bodies scrolled past
- GIVEN 100 bodies left to download and the list scrolled down
- WHEN 48 have landed
- THEN the strip shows in the app bar, its bar near one half

## REMOVED Requirements
