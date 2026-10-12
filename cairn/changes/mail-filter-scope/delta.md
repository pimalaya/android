---
cairn: delta
change: mail-filter-scope
---

Folds into `spec/mail.md` (the hidden All Mail, the list's narrowing, what syncs, Load more) and `spec/offline-store.md` (the filter's defaults, the role chips).

## ADDED Requirements

### Requirement: A Gmail account keeps its step's messages under the hood
A Gmail account SHALL hold a hidden collection standing for its account-wide step, Gmail's All Mail, every message a step brings landing in it beside the mailboxes its labels name, a message filed under no mailbox label (archived) landing in it alone, with no body. It SHALL be among the mailboxes a step asks the store whether it holds a message in, so no step reads a message twice, and SHALL NOT be listed: not on the filter page, not under a chip, not in a list or a search. A step over the trash or the junk alone SHALL ask over that mailbox alone, what lies there having been taken out of every other.

#### Scenario: An archived message
- GIVEN a Gmail message filed under no mailbox label
- WHEN a step brings it
- THEN it is stored, no mailbox shows it, and the next step does not read it again

## MODIFIED Requirements

### Requirement: The mail list narrows what it shows
The mail list SHALL list every stored message of every mailbox the filter lets through, and SHALL offer a search over the sender and the subject, the role chips, an unread chip and an attachments chip, all answered by the store over every stored message. None of these SHALL sync by itself: opening a mailbox, or turning a chip on, shows what the store holds. A pull while a role chip is on SHALL sync that role's mailboxes of the shown accounts alone; otherwise what syncs is what the filter ticks.

#### Scenario: Unread only
- GIVEN read and unread mail in two accounts
- WHEN the unread chip is on
- THEN the list shows both accounts' unread messages and nothing else

#### Scenario: The trash opened
- GIVEN a Gmail account whose trash was never listed
- WHEN the Trash chip is chosen
- THEN nothing syncs, and the list shows what the phone holds in it

### Requirement: A sync skips what the filter hides
A mail pull, the drawer's sync and a background run SHALL reconcile the mailboxes the filter ticks and SHALL NOT reconcile a mailbox it unticks, nor drain an outbox it unticks; a pull SHALL also skip the accounts the filter hides, the drawer's sync and a background run taking every account that is on. A pull under a role chip SHALL reconcile that role's mailboxes of the shown accounts, ticked or not, and nothing else.

#### Scenario: A hidden mailbox
- GIVEN a mailbox unchecked in the filter
- WHEN the mail list is pulled down, or the drawer's sync runs
- THEN the mailbox is still offered by the filter, and nothing in it is read or pushed

#### Scenario: A pull under the Trash chip
- GIVEN the trash unticked and the Trash chip on
- WHEN the mail list is pulled down
- THEN the shown accounts' trashes are reconciled, and no other mailbox

### Requirement: The mail list ends on Load more
While a shown account that is on has more to load, the mail list SHALL end on a footer: a button, *Load more*, over a line saying about how many messages a step brings each account (*About 50 more per account*). An account shown by one of its mailboxes alone has more to load while that mailbox's frontier is not all mail. A tap SHALL take one step of each such account, one after another under the sync dock, then read the list again and download the bodies left. While a step or any other pass holds the sync (a tap's, the first sync's, a pull's, the drawer's, a background run's), the button SHALL be disabled and read *Loading more*, so a second tap queues nothing, and SHALL read *Load more* again once every account's step ended. Off Wi-Fi the line SHALL add that it waits for Wi-Fi and the button SHALL be disabled. Mailboxes kept whole SHALL take no part. The junk and the trash, which no step of their account brings, SHALL take part only when their account shows one of them alone: a tap SHALL then step it by its label on Gmail (`labelIds` `SPAM` or `TRASH` with `includeSpamTrash`), and elsewhere widen it by its next chunk of headers, its bodies left to opening, until it is listed down to its account's bound. The footer SHALL hide under a search, and SHALL stand in for an empty list's empty state.

#### Scenario: Two accounts
- GIVEN accounts A and B shown together, both with older mail
- WHEN *Load more* is tapped
- THEN the button reads *Loading more* and is disabled while A then B take a step, and the list reaches down to the more recent of their new frontiers

#### Scenario: Gmail's spam
- GIVEN a Gmail account whose spam was never listed, shown alone
- WHEN *Load more* is tapped
- THEN about 50 of its newest spam messages are brought

#### Scenario: During the first sync
- GIVEN an account's first sync running
- WHEN the list's end is reached
- THEN the footer reads *Loading more*, disabled

#### Scenario: Off Wi-Fi
- GIVEN a list on mobile data
- WHEN its end is reached
- THEN the footer says it waits for Wi-Fi, and its button does nothing

### Requirement: The filter lists accounts and their collections
The filter SHALL be a page per domain listing each account that is on and covers the domain with a checkbox, and its collections indented below it with theirs: the mailboxes and the account's outbox, the calendars, or the subscribed address books, the on-device book listed as an account of its own. It SHALL be keyed by collection id and kept across restarts, per domain, a store with no kept filter showing everything but an account's junk and trash, each unticked the first time a roster lists it and left as the user ticks it after. An account's checkbox SHALL read ticked when all its collections show, partly ticked when some do, unticked when none do or the account is hidden. Unticking an account SHALL hide it and keep its collections' own choices; ticking it back SHALL restore them, or tick them all when they tick none. Ticking a collection of a hidden account SHALL show the account with that collection alone. A Reset SHALL show everything again but the junk and the trash. Every tick SHALL apply to the list at once. The boxes SHALL be checkboxes, not switches, to stay apart from an account's on and off. The bar's filter button SHALL read active only when something beyond that default is hidden.

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
- THEN the calendar is still hidden, and nothing is hidden in mail or contacts beyond the junk and the trash

#### Scenario: A new account
- GIVEN a mail account just connected
- WHEN the filter is opened
- THEN its junk and its trash are unticked, every other mailbox ticked

### Requirement: A role gives direct access across accounts
The mail list SHALL offer role chips, Inbox, Sent, Drafts, Trash, Junk and Archive, at most one on: a chip SHALL narrow the list to the mailboxes holding that role (pimdir's collection role, as the source states it) of the accounts the filter shows, ticked or not, a chip being a view of its own, and no chip on SHALL narrow nothing. The outbox holds no role. Gmail, listed as one account, SHALL hold the roles of its system labels (INBOX, SENT, DRAFT, TRASH and SPAM as inbox, sent, drafts, trash and junk); Gmail has no archive label, so Archive SHALL show none of its mail. Contacts and calendars SHALL offer a Default chip narrowing to the default collection of every shown account. A chip SHALL NOT sync by itself; a pull while it is on syncs what it shows.

#### Scenario: Every trash
- GIVEN three shown mail accounts, their trashes unticked
- WHEN the Trash chip is chosen
- THEN the list shows the three trashes merged, and nothing syncs

## REMOVED Requirements

### Requirement: Junk and trash list when opened
Replaced by *Load more* over the junk or the trash shown alone: opening a mailbox syncs nothing.
