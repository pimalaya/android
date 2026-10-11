---
cairn: delta
change: sync-feedback-pinned
---

Folds into `spec/offline-store.md` (the strip) and `spec/mail.md` (the step's count, the background download, the footer).

## ADDED Requirements

### Requirement: A mail step counts the bodies it brings
A mail step SHALL fill its account's part of the strip's bar with the bodies it brought out of the bodies it planned: about 50 for a step the bridge walks (Gmail, Graph, JMAP), what it brought once the walk answers; for IMAP, the bodies its listing left to read, known once its mailboxes are listed. A body SHALL count once its file is written, as the backend writes it (Gmail by batch of 10, Graph by `$batch`, IMAP as each streamed body starts, JMAP one blob at a time). The bar SHALL run indeterminate while the first account lists. A pass of steps over several accounts SHALL weigh each account alike.

#### Scenario: Load more on Gmail
- GIVEN a Gmail account with older mail
- WHEN *Load more* is tapped
- THEN the bar runs indeterminate while the step lists, then fills ten bodies at a time, and is full once the 50th is written

#### Scenario: Two accounts
- GIVEN accounts A and B shown together, both with older mail
- WHEN *Load more* is tapped and A's step ends
- THEN the bar stands at one half, and B's bodies carry it on

## MODIFIED Requirements

### Requirement: A sync says what it is working on, in every domain
A running sync SHALL show, pinned under the app bar of every list (mail, contacts, calendars) and above the list, so it shows wherever the list is scrolled, a strip naming what the pass is on over one thin bar: the account on a first line once the pass reaches one (*me@example.org*), the domain on the line below (*Syncing contacts*), never the engine's steps. The three domains SHALL report alike, so a wait reads the same whichever one is being synced. The strip SHALL hide when nothing runs.

The bar SHALL be one progress over the whole pass, every account and every domain it runs, and SHALL never move back while the pass runs. Before it starts, the pass SHALL be sized in sections, one per domain and account, each weighing the collections stored for it, one at least, an account's mail step weighing one; a section SHALL fill its weight however many collections its listing turns up, and sections MAY fill at different speeds. Within a section, the pass SHALL tell how many collections it will run before the first one runs, and each collection SHALL fill its share as it lands; within it, a step moving items batch by batch, the members a listing did not carry being read or the contacts and events being written to the phone, SHALL fill part of that share, the download the first three fifths and the phone the rest where the domain has a phone mirror, the download all of it for mail; a calendar's steps only while that calendar runs alone; a mail step its bodies (A mail step counts the bodies it brings). The bar SHALL run indeterminate only before anything is counted, its line then saying the sync is starting (*Starting the sync*) rather than naming a domain, and SHALL keep the determinate bar's track, height and margins.

While the strip shows a pass, the list's search field and its chips SHALL be hidden, and SHALL come back when it goes; a search field holding a query SHALL stay. The list's count line SHALL stay.

The strip SHALL NOT block the app: the lists, the reader and the composer stay usable while it runs, the lists refreshing when the pass ends. One pass SHALL run at a time, a pull or the drawer's sync asked for meanwhile doing nothing, and an account SHALL NOT be deleted while a pass runs. The strip's line SHALL never be empty: it SHALL open saying the sync is starting, and SHALL follow the pass to the next account and domain once something is counted.

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
- THEN the strip shows under the app bar, its bar filling, without scrolling back up

#### Scenario: The controls step aside
- GIVEN the mail list with an empty search field and its chips
- WHEN a pass starts
- THEN the strip shows above the list, the search field and the chips hidden, the count line kept
- AND both come back once the pass ends

#### Scenario: Reading while it runs
- GIVEN a sync running
- WHEN a message is opened, or a sync pulled for
- THEN the message opens, and no second pass starts

### Requirement: The mail list shows its background download
While the background fill downloads older mail or bodies and no pass runs, the mail list's pinned strip SHALL read *Loading older mail* beside a download glyph pulsing gently in opacity, holding still when the system removes animations, a long press on the glyph naming it. While bodies download it SHALL show a bar of the bodies landed out of the run's plan, every account's summed, a body counting once its file is written and a replan adding to the plan; the header fill alone has no bar. The bar SHALL never move back while the fill runs. The strip SHALL show only once a step downloaded something, and SHALL go when the fill ends or pauses (the app left, no Wi-Fi).

#### Scenario: After the first mail sync
- GIVEN a first mail sync that landed the newest messages of an IMAP account
- WHEN the fill widens its mailboxes in the background
- THEN the strip pulses its glyph above the list, and goes once the fill stops

#### Scenario: Bodies scrolled past
- GIVEN 100 bodies left to download and the list scrolled down
- WHEN 48 have landed
- THEN the strip shows above the list, its bar near one half

### Requirement: The mail list ends on Load more
While a shown account that is on has more to load, the mail list SHALL end on a footer: a button, *Load more*, over a line saying about how many messages a step brings each account (*About 50 more per account*). An account shown by one of its mailboxes alone has more to load while that mailbox's frontier is not all mail. A tap SHALL take one step of each such account, one after another under the sync strip, then read the list again and download the bodies left. While a step or any other pass holds the sync (a tap's, the first sync's, a pull's, the drawer's, a background run's), the button SHALL be disabled and read *Loading more*, so a second tap queues nothing, and SHALL read *Load more* again once every account's step ended. Off Wi-Fi the line SHALL add that it waits for Wi-Fi and the button SHALL be disabled. Junk, trash and mailboxes kept whole SHALL take no part. The footer SHALL hide under a search, and SHALL stand in for an empty list's empty state.

#### Scenario: Two accounts
- GIVEN accounts A and B shown together, both with older mail
- WHEN *Load more* is tapped
- THEN the button reads *Loading more* and is disabled while A then B take a step, and the list reaches down to the more recent of their new frontiers

#### Scenario: During the first sync
- GIVEN an account's first sync running
- WHEN the list's end is reached
- THEN the footer reads *Loading more*, disabled

#### Scenario: Off Wi-Fi
- GIVEN a list on mobile data
- WHEN its end is reached
- THEN the footer says it waits for Wi-Fi, and its button does nothing

## REMOVED Requirements
