---
cairn: delta
change: full-mail-index
---

## ADDED Requirements

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within the account's bound, newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph message delta page, 100 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`; Gmail from its metadata read; JMAP from `Email/get`'s summary properties. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within the bound; mail outside the bound SHALL never be deleted by a sync.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 100k messages and an empty store
- WHEN it is synced
- THEN the newest messages are listed once the first page lands, before the pass ends
- AND once it ends, every message is listed with its subject, sender and date

#### Scenario: An interrupted first pass
- GIVEN a first pass cut off after its first page
- WHEN the mailbox is synced again
- THEN it resumes below the last landed page rather than from the top

#### Scenario: A bounded account
- GIVEN an account bounded to the last 6 months
- WHEN it is synced
- THEN older messages are neither fetched nor listed, and none already stored is deleted

### Requirement: An account bounds its mail
An account's settings SHALL offer to sync all of its mail or the last 1, 3, 6, 12 or 24 months, the floor being the first day of the month that many months back, on the `Date` header (a message with no usable date in every scope). A provider's received-date filter SHALL only narrow a listing, two days below the floor (IMAP `SENTSINCE` one day below). Widening the bound SHALL have the next sync list what it now lacks: the band below the old floor where the backend's checkpoint is not bound to a scope (IMAP, Gmail, JMAP), the whole wider scope where it is (Graph). Narrowing it SHALL collect the stored messages dated below the new floor that owe nothing to the server, their mailboxes keeping them there.

#### Scenario: Narrowing to a year
- GIVEN an account syncing all of its mail
- WHEN its bound is set to the last year
- THEN the messages older than that leave the store, except one with a change not pushed yet
- AND nothing is deleted on the server

#### Scenario: Widening again
- GIVEN that account
- WHEN its bound is set back to all mail and it is synced
- THEN the older messages are listed again

### Requirement: The mail list loads lazily
The mail list SHALL hold only the rows near the scroll position, read a page at a time from the store and the far pages evicted, sized by a count of what the filter, the chips and the search let through, with a placeholder row while a page loads. It SHALL place its day headers from one count per day, without loading rows. The messages waiting to go out SHALL stay on top, outside the paged query. Search, the chips and the unread badge SHALL be conditions of the store's query and cover every stored message. A list showing the store SHALL redraw as a pass's pages land.

#### Scenario: Scrolling to old mail
- GIVEN 100k stored messages
- WHEN the list is flung to its end
- THEN the oldest message is shown, and memory holds a bounded number of rows

#### Scenario: Searching old mail
- GIVEN a message from three years ago, stored
- WHEN its sender is searched with no network
- THEN it is found

### Requirement: A listing's text is decoded before it is stored
A subject and a sender's name a listing reads SHALL be stored with their RFC 2047 encoded words decoded, through io-pimdir's Annex A derivation of the header fields the listing read; JMAP and Graph hand the decoded value over already. A value carrying no encoded word SHALL be stored as it came.

#### Scenario: A subject in another script
- GIVEN a message whose subject the sender wrote as encoded words
- WHEN its mailbox is listed
- THEN the list row shows the subject, not `=?UTF-8?B?...?=`

### Requirement: The attachment mark is corrected by the body
A listing SHALL mark a message as carrying an attachment from the source's own flag where it states one (Graph `hasAttachments`, JMAP `hasAttachment`), else when its top-level `Content-Type` is `multipart/mixed`. Opening the message SHALL replace that mark with the one the walk of its parts gives.

#### Scenario: A list footer
- GIVEN a `multipart/mixed` message carrying no attachment, listed with a paperclip
- WHEN it is opened
- THEN its row loses the paperclip

### Requirement: Nothing reaches the store unnamed
Every member a listing carries SHALL arrive named (pimdir SYNC section 4). A DAV, Google or Graph contacts or calendar listing that answers handles and revisions SHALL be named before it reaches the engine: a member the source already binds at an unchanged revision by the link id the store holds, any other by its body, read 64 at a time and carried in the listing. A write naming no identity on a handle no binding holds SHALL be refused, and a handle bound to another link id SHALL have that binding retired first: a handle names one item per source.

#### Scenario: A new card on a CardDAV book
- GIVEN a book whose listing reports a card the store has never seen
- WHEN it is synced
- THEN the card lands named, with its body, in the same pass

#### Scenario: A resource replaced in place
- GIVEN a handle bound to one link id
- WHEN an upsert of the same handle carries another
- THEN the binding it held is retired and the handle binds the new identity alone

### Requirement: A round lands page by page
The store SHALL keep, per collection and source, the round under way (its scope, its resume cursor, the checkpoint a page handed) and the coverage the last closed round left, read and written through io-pimdir's canonical statements; it SHALL stamp every binding a page lists with the round's id, and hand the engine, while a round is open, the bindings no page stamped whose date is in its scope or unknown.

#### Scenario: A round cut off
- GIVEN a round whose first page landed
- WHEN the collection is loaded
- THEN the round, its cursor and its scope come back with it

## MODIFIED Requirements

### Requirement: The mail list narrows what it shows
The mail list SHALL list every stored message of every mailbox the filter lets through, and SHALL offer a search over the sender and the subject, an unread chip and an attachments chip, all answered by the store over every stored message. None of these SHALL change what syncs, which the filter alone decides.

#### Scenario: Unread only
- GIVEN read and unread mail in two accounts
- WHEN the unread chip is on
- THEN the list shows both accounts' unread messages and nothing else

### Requirement: The mail list selects
A long press on a message SHALL start a selection holding it, and while one runs a tap SHALL add or remove a message rather than open it. The bar SHALL then carry the count, read or unread, star or unstar, delete after asking, and select-all, each over the whole selection, a toggle going the way that changes something. The selection SHALL be keyed by store id, and select-all SHALL select what the list's query lets through, read whole only when the bar acts on it. Back SHALL clear the selection.

#### Scenario: Starring three messages
- GIVEN three messages, one of them starred
- WHEN they are selected and the bar's star is pressed
- THEN all three are starred

### Requirement: A message's date is its Date header
A message's date SHALL be its `Date` header, which pimdir STORAGE Annex A.1 stores and sorts mail by, on every backend: the header itself on IMAP and Gmail (asked for among the metadata headers), `sentDateTime` on Graph, `sentAt` on JMAP. A message with no date SHALL be stored with none, never with its reception time in its place.

#### Scenario: A message received late
- GIVEN a message sent at 08:00 and received at 08:05
- WHEN its mailbox is synced
- THEN the message's date is 08:00

## REMOVED Requirements

### Requirement: An unnamed handle is a probe
Every member a listing carries arrives named now (Nothing reaches the store unnamed), and the `probes` table is gone from pimdir's schema.

### Requirement: An envelope's text is decoded before it is stored
Replaced by A listing's text is decoded before it is stored: no listing reads an `ENVELOPE` any more.
