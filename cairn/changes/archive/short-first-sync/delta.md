---
cairn: delta
change: short-first-sync
---

## ADDED Requirements

### Requirement: A mailbox is listed a chunk at a time
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be the oldest `Date` among the 50 newest the source names (the last 50 UIDs `UID SEARCH` finds, Graph's `$top` ordered by `sentDateTime`, Gmail's `messages.list`, JMAP's `Email/query` by `receivedAt`), or no floor when it holds fewer, within the account's bound. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta. Widening a mailbox SHALL take the next chunk below its floor, the oldest `Date` among the newest messages dated before it, and list the band it lacks where the backend's checkpoint is bound to no scope (IMAP, Gmail, JMAP), the whole wider scope where it is (Graph).

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 3,000 messages never listed
- WHEN it is synced
- THEN its 50 newest are listed, and its floor is the oldest `Date` among them

#### Scenario: A small mailbox
- GIVEN a mailbox of 20 messages
- WHEN it is synced the first time
- THEN all of them are listed, and nothing is left below its floor

#### Scenario: Widening
- GIVEN a mailbox listed down to its 50th newest message
- WHEN it is widened
- THEN the next 50 below the floor are listed, the band alone on IMAP, Gmail and JMAP

### Requirement: The merged list reaches down to its mailboxes' floor
The merged list SHALL show no message older than the most recent floor among the mailboxes it shows, so no mailbox's older mail is listed while another's of the same days is not stored yet; a search SHALL still cover every stored message. While a floor holds mail back, the list SHALL end on a row that, once reached, widens by one chunk each shown mailbox holding that floor and reads the list again, or says older mail needs the network when there is none.

#### Scenario: Two mailboxes at different depths
- GIVEN an inbox listed down to Tuesday and a sent mailbox down to the week before
- WHEN the list is scrolled to its end
- THEN it ends at Tuesday, and the inbox's next chunk is listed

#### Scenario: Offline
- GIVEN no network
- WHEN the list's end is reached while a floor holds mail back
- THEN the last row says older mail needs the network, and nothing is asked for

### Requirement: Older mail fills in behind
After a mail tab's first sync, and on every return to the app or pass after it, every mailbox SHALL widen 500 messages at a time toward its account's bound, with no dialog, the inbox and the sent mail first, a mailbox never listed before one that only lacks older mail, and among them the one holding the most recent floor. The fill SHALL run only while the app is in the foreground, on a network that is not metered, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers.

#### Scenario: Leaving the app
- GIVEN a fill under way
- WHEN the app goes to the background
- THEN no further chunk is listed, and the next return resumes below the floors reached

#### Scenario: A metered network
- GIVEN a phone on mobile data
- WHEN the first dialog closes
- THEN only the first chunks are stored, until an unmetered network is back

### Requirement: A pass takes the inbox first
Every mail pass SHALL take an account's mailboxes in the order of the role each source states: the inbox, the sent mail, the drafts, every other by name, the junk and the trash last. The roles SHALL be stored as pimdir's collection roles (STORAGE section 14): RFC 6154 attributes and `INBOX` on IMAP, RFC 8621 roles, Gmail's system labels, Graph's well-known folders.

#### Scenario: A Graph account
- GIVEN Graph listing *Sent Items*, *Projets* and *Inbox* in that order
- WHEN the account is synced
- THEN *Inbox* is synced first and *Sent Items* second

### Requirement: Each page's time is logged
A mail pass SHALL log, page by page, how many messages a page listed and the time spent on the network, on the JSON this side reads and writes, in the engine, and in the store's loads and writes.

#### Scenario: A first round
- GIVEN a debug build
- WHEN a mailbox's page lands
- THEN the log names its count and the four times

### Requirement: A load carries each message's date
A store load SHALL carry each mail placement's `Date` beside its state, which the bridge SHALL hand the engine as a summary holding the date alone and SHALL NOT write back: a write handing it back unchanged SHALL carry no summary, leaving the stored row alone. The engine reads it to tell a placement in a round's scope from one outside it (SYNC section 5).

#### Scenario: A band listed in one page
- GIVEN a mailbox listed down to Tuesday
- WHEN a widening lists the week before in one page
- THEN the messages above Tuesday stay, none found absent from a band they are not in

#### Scenario: A marker staged on a listed message
- GIVEN a message listed with its subject and sender
- WHEN a marker is staged on it and the next sync pushes it
- THEN its subject and sender are still stored

## MODIFIED Requirements

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within its scope (its floor, within the account's bound), newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph message delta page, 100 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`; Gmail from its metadata read; JMAP from `Email/get`'s summary properties. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within its scope; mail outside it SHALL never be deleted by a sync. The chunks and the fill (A mailbox is listed a chunk at a time, Older mail fills in behind) SHALL bring a mailbox whole within the account's bound.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 100k messages and an empty store
- WHEN it is synced
- THEN its newest chunk is listed with its subject, sender and date before the dialog closes
- AND the rest is listed behind it, a chunk at a time

#### Scenario: An interrupted first pass
- GIVEN a round cut off after its first page
- WHEN the mailbox is synced again
- THEN it resumes below the last landed page rather than from the top

#### Scenario: A bounded account
- GIVEN an account bounded to the last 6 months
- WHEN it is synced and filled
- THEN older messages are neither fetched nor listed, and none already stored is deleted

### Requirement: An account bounds its mail
An account's settings SHALL offer to sync all of its mail or the last 1, 3, 6, 12 or 24 months, the floor being the first day of the month that many months back, on the `Date` header (a message with no usable date in every scope). A mailbox's floor SHALL never go below the bound: a chunk reaching past it stops at it, and a mailbox whose floor is the bound is whole. A provider's received-date filter SHALL only narrow a listing, two days below the floor (IMAP `SENTSINCE` one day below). Widening the bound SHALL have the fill carry on below the old floor, chunk by chunk. Narrowing it SHALL collect the stored messages dated below the new floor that owe nothing to the server, their mailboxes keeping them there.

#### Scenario: Narrowing to a year
- GIVEN an account syncing all of its mail
- WHEN its bound is set to the last year
- THEN the messages older than that leave the store, except one with a change not pushed yet
- AND nothing is deleted on the server

#### Scenario: Widening again
- GIVEN that account
- WHEN its bound is set back to all mail and the app stays open on an unmetered network
- THEN the older messages are listed again, a chunk at a time

### Requirement: The mail list loads lazily
The mail list SHALL hold only the rows near the scroll position, read a page at a time from the store and the far pages evicted, sized by a count of what the filter, the chips, the search and the list's floor let through, with a placeholder row while a page loads. It SHALL place its day headers from one count per day, without loading rows. The messages waiting to go out SHALL stay on top, outside the paged query. Search, the chips and the unread badge SHALL be conditions of the store's query, search and the badge covering every stored message. A list showing the store SHALL redraw as a pass's pages land.

#### Scenario: Scrolling to old mail
- GIVEN 100k stored messages in one mailbox listed whole
- WHEN the list is flung to its end
- THEN the oldest message is shown, and memory holds a bounded number of rows

#### Scenario: Searching old mail
- GIVEN a message from three years ago, stored
- WHEN its sender is searched with no network
- THEN it is found

### Requirement: A connected account is synchronised before the app shows it
Finishing the connection flow SHALL land on the list of the first domain the account covers, mail first, and each domain the account covers SHALL owe its first sync to the first time its tab is reached: that tab SHALL sync that domain alone, behind the modal dialog, before showing it. A first sync that failed SHALL stay owed, tried again on the next visit. The user SHALL NOT have to refresh a domain to see what was just connected.

#### Scenario: An account covering three domains
- GIVEN a connection flow that connected mail, contacts and calendars
- WHEN it finishes
- THEN the app lands on the mail list, whose dialog syncs the newest chunk of each mailbox and nothing else
- AND the contacts and the calendars sync, each behind its dialog, the first time their tab is opened

#### Scenario: One domain fails
- GIVEN a first calendar sync that fails
- WHEN the calendar tab is opened again
- THEN its first sync runs again, the domains that succeeded untouched

## REMOVED Requirements
