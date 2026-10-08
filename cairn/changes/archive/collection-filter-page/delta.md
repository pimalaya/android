---
cairn: delta
change: collection-filter-page
---

## ADDED Requirements

### Requirement: The filter lists accounts and their collections
The filter SHALL be a page per domain listing each account that is on and covers the domain with a checkbox, and its collections indented below it with theirs: the mailboxes and the account's outbox, the calendars, or the subscribed address books, the on-device book listed as an account of its own. It SHALL be keyed by collection id and kept across restarts, per domain, a store with no kept filter showing everything. An account's checkbox SHALL read ticked when all its collections show, partly ticked when some do, unticked when none do or the account is hidden. Unticking an account SHALL hide it and keep its collections' own choices; ticking it back SHALL restore them, or tick them all when they tick none. Ticking a collection of a hidden account SHALL show the account with that collection alone. A Reset SHALL show everything again. Every tick SHALL apply to the list at once. The boxes SHALL be checkboxes, not switches, to stay apart from an account's on and off.

#### Scenario: Two accounts with an Archive
- GIVEN two mail accounts each holding a mailbox named Archive
- WHEN one account's Archive is unticked
- THEN the other account's Archive still shows

#### Scenario: An account unticked and back
- GIVEN an account with its Archive unticked
- WHEN the account is unticked, then ticked again
- THEN it shows again without its Archive

#### Scenario: After a restart
- GIVEN a calendar unticked
- WHEN the app is restarted
- THEN the calendar is still hidden, and nothing is hidden in mail or contacts

### Requirement: An account that is off takes no part
An account's settings SHALL switch the account on and off. An account that is off SHALL NOT be synced by any sync (the drawer's, a list's pull, a first sync, the mail fill), SHALL NOT be listed by a filter, and its items SHALL NOT be listed. Its data SHALL stay stored, and switching it back on SHALL bring both back. The phone's mirror of its address books is local and SHALL be left as it is.

#### Scenario: A work account on the weekend
- GIVEN an account switched off in its settings
- WHEN the drawer's sync runs
- THEN the account is not contacted, its card says Deactivated, and no list or filter shows it

### Requirement: A role gives direct access across accounts
The mail list SHALL offer role chips, Inbox, Sent, Drafts, Trash, Junk and Archive, at most one on: a chip SHALL narrow the collections the filter shows to those holding that role (pimdir's collection role, as the source states it), and no chip on SHALL narrow nothing. The outbox holds no role. Gmail, listed as one account, SHALL hold the roles of its system labels (INBOX, SENT, DRAFT, TRASH and SPAM as inbox, sent, drafts, trash and junk); Gmail has no archive label, so Archive SHALL show none of its mail. Contacts and calendars SHALL offer a Default chip narrowing to the default collection of every shown account. No chip SHALL change what syncs.

#### Scenario: Every trash
- GIVEN three shown mail accounts
- WHEN the Trash chip is chosen
- THEN the list shows the three trashes merged

### Requirement: The source states a collection's default and whether it takes writes
Listing an account's calendars or address books SHALL say which one the source writes to when none is named, stored as pimdir's `default` role: JMAP `isDefault`, Graph's `isDefaultCalendar` and its default contacts folder, Google's `primary` calendar and its `myContacts` group. CalDAV and CardDAV state none. It SHALL also say which ones the user may not write into: JMAP `myRights`, Graph `canEdit`, Google's `accessRole` below writer; rights a source leaves unsaid SHALL read as writable. A contacts sync SHALL restate both for the account's books before it reconciles them.

#### Scenario: A source moving its default
- GIVEN a calendar the source named default
- WHEN the source names another one
- THEN the store's default role moves to it in the same listing

### Requirement: A new contact or event goes to the default collection
A collection SHALL be its account's default when its source states it and it is writable, else when it is the account's only writable one of its kind, else when the user set it so with "Set as default" on the filter page, which the app keeps and never writes to pimdir. A new contact or event SHALL go to the default of the account in view, the one account the filter shows, without asking. Otherwise the app SHALL ask, offering writable collections only, the default preselected; a single writable one on offer SHALL be taken without asking.

#### Scenario: A Google account
- GIVEN a Google account with several calendars
- WHEN an event is created
- THEN it goes to the primary calendar without asking

#### Scenario: A read-only calendar
- GIVEN an account with a read-only holidays calendar and two writable calendars, none named default
- WHEN an event is created
- THEN the two writable calendars alone are offered, and once one is set as default the next event goes there

## MODIFIED Requirements

### Requirement: A list's pull syncs what it shows
Pulling a list down SHALL sync that list's domain alone, and within it only the accounts and collections its filter shows, never an account that is off; the contacts pull SHALL also run the phone's pass. The drawer's sync SHALL take every domain, and every collection of every account that is on, whatever the filters hide.

#### Scenario: Pulling the agenda
- GIVEN two calendar accounts, one hidden by the filter
- WHEN the agenda is pulled down
- THEN only the shown account's calendars are synced, and no mail or contact is

### Requirement: The filter offers what it can hide
The filter page SHALL list the accounts that are on and cover the domain on screen, each with its collections, and SHALL list the on-device book under its own entry on contacts alone. An account holding no collection of the domain SHALL NOT be listed.

#### Scenario: A contacts-only account on the mail list
- GIVEN an account connected for contacts alone
- WHEN the filter is opened over the mail list
- THEN the account is not listed

### Requirement: The bottom bar switches domains
A bottom navigation bar SHALL switch between mail, contacts and calendars, in that order, the one on screen on a neutral indicator, mail carrying the count of unread messages among those listed. It SHALL show on the three lists only. The drawer SHALL open on the app's name beside a closing cross, then one card per account naming its address and the domains it covers, with a pill saying Deactivated when the account is off and otherwise when it last synced, then, fixed at the bottom, a line and the actions. Pressing a card SHALL open that account's settings. The drawer SHALL NOT list mailboxes. A list screen's bar SHALL carry the burger and SHALL NOT carry the domain buttons.

#### Scenario: Switching to the calendars
- GIVEN the mail list
- WHEN the calendars item of the bottom bar is pressed
- THEN the agenda is swapped in with no slide, the calendars item on the indicator

#### Scenario: An account that is off
- GIVEN an account switched off in its settings
- WHEN the drawer opens
- THEN its card's pill says Deactivated

### Requirement: A sync skips what the filter hides
A mail pull SHALL list the shown accounts' mailboxes and SHALL NOT reconcile a mailbox the filter hides, by its account or by its collection, nor drain an outbox it hides. The drawer's sync SHALL reconcile every mailbox of every account that is on.

#### Scenario: A hidden mailbox
- GIVEN a mailbox unchecked in the filter
- WHEN the mail list is pulled down
- THEN the mailbox is still offered by the filter, and nothing in it is read or pushed

### Requirement: The mail list narrows what it shows
The mail list SHALL list every stored message of every mailbox the filter lets through, and SHALL offer a search over the sender and the subject, the role chips, an unread chip and an attachments chip, all answered by the store over every stored message. None of these SHALL change what syncs, which the filter alone decides.

#### Scenario: Unread only
- GIVEN read and unread mail in two accounts
- WHEN the unread chip is on
- THEN the list shows both accounts' unread messages and nothing else

### Requirement: A message is sent through an outbox
Submitting SHALL compose the message on the device and stage it as one action on the store's queue: the app's own `submit` kind, a versioned payload naming the sender and what a listing draws, and the composed message written to the blob directory and pinned by the enqueue, all in the one transaction the standard prescribes for a producer. The payload carries the subject and the date because a waiting message is not an item and has no summary row beside it, and parsing a message to draw a list is what the sort key exists to avoid.

The queued actions of an account SHALL be shown as its outbox, above everything the store synced, and discarding one SHALL cancel its row and release its pin, there being nothing anywhere to tell. A parked one SHALL be shown too, saying it was refused rather than that it is waiting: a message the sender wrote is not something to drop quietly.

The waiting messages SHALL be filed under each account's own *Outbox*, which the filter page SHALL offer first among the account's mailboxes, so they can be shown alone or hidden like any mailbox.

#### Scenario: Composed with no network
- GIVEN no network
- WHEN a message is sent
- THEN its action is queued, the message shows as pending, and the composer closes

#### Scenario: A refresh over it
- GIVEN a message waiting to be sent
- WHEN the account's mailboxes are re-listed
- THEN it is still there, a queue row being no collection's to replace

#### Scenario: Discarding one
- GIVEN a queued message
- WHEN it is discarded
- THEN its row is cancelled and the body it pinned is released

## REMOVED Requirements
