---
cairn: delta
change: mail-filter-apply
---

Folds into `spec/offline-store.md` (the filter page and its Apply) and `spec/mail.md` (the first step, *Load more* per mailbox, the frontiers, the JMAP step).

## ADDED Requirements

### Requirement: Applying the filter syncs what it newly takes
The filter page SHALL hold its ticks as a draft, the lists behind it unchanged, until *Apply*, which keeps them; closing the page SHALL drop them. Applying SHALL start, under the sync dock and on Wi-Fi (once it is back otherwise), the first sync of every mailbox newly shown and never listed: its first chunk of about 50 newest headers, its own frontier where that chunk reached, its bodies downloaded after it; a mailbox listed before SHALL be caught up by the next pull. A book or a calendar newly shown SHALL start its domain's pull. A collection unticked SHALL be hidden and synced no more, by any sync, its bodies' download and the background fill included, its stored items kept and what it owes waiting for it.

#### Scenario: Ticking Sent on Microsoft
- GIVEN a Microsoft account whose Sent was never listed
- WHEN Sent is ticked and *Apply* pressed
- THEN Sent's newest messages are listed under the sync dock and their bodies downloaded after

#### Scenario: Closing without applying
- GIVEN Sent ticked on the filter page
- WHEN the page is closed by its cross
- THEN Sent is still unticked, and nothing syncs

## MODIFIED Requirements

### Requirement: The filter lists accounts and their collections
The filter SHALL be a page per domain listing each account that is on and covers the domain with a checkbox, and its collections indented below it with theirs: the mailboxes and the account's outbox, the calendars, or the subscribed address books, the on-device book listed as an account of its own. It SHALL be keyed by collection id and kept across restarts, per domain. A store with no kept filter SHALL show everything but, on a Gmail account, its trash and its spam, and on any other mail account listed for the first time, every mailbox but its inbox, each unticked the first time a roster lists it (a mailbox the server adds later included) and left as the user ticks it after; a mail account listed before this rule SHALL keep its ticks, its junk and trash aside. An account's checkbox SHALL read ticked when all its collections show, partly ticked when some do, unticked when none do or the account is hidden. Unticking an account SHALL hide it and keep its collections' own choices; ticking it back SHALL restore them, or tick them all when they tick none. Ticking a collection of a hidden account SHALL show the account with that collection alone. A Reset SHALL go back to the default. The boxes SHALL be checkboxes, not switches, to stay apart from an account's on and off. The bar's filter button SHALL read active only when something the default shows is hidden.

#### Scenario: Two accounts with an Archive
- GIVEN two IMAP accounts each holding a mailbox named Archive, their inboxes alone ticked
- WHEN one account's Archive is ticked and applied
- THEN the other account's Archive stays hidden

#### Scenario: An account unticked and back
- GIVEN an account with its Archive unticked
- WHEN the account is unticked, then ticked again
- THEN it shows again without its Archive

#### Scenario: After a restart
- GIVEN a calendar unticked
- WHEN the app is restarted
- THEN the calendar is still hidden, and nothing is hidden in mail or contacts beyond the default

#### Scenario: A new account
- GIVEN a Microsoft account just connected, and a Gmail one
- WHEN the filter is opened
- THEN the Microsoft account ticks its inbox alone, the Gmail one every label but Trash and Spam

### Requirement: An account's mail comes in steps
An account's mail SHALL come in steps: a step SHALL bring about 50 messages whole (headers and body, plain `Full`), older than what it holds, never counted by an offset, on the account's one session, its reads in small batches one after another. Each backend SHALL decide how a step finds them and batches them. The first sync SHALL be one step of the account over the mailboxes the filter ticks: the inbox alone on an account other than Gmail, every label but Trash and Spam on Gmail. After it, a Gmail account SHALL step as a whole (All Mail), and any other account mailbox by mailbox. A step SHALL leave out the junk and the trash. A view filtered to one mailbox of an account that has several SHALL step that mailbox alone. A step finding nothing older SHALL leave the account at all mail, or the one mailbox it stepped alone.

#### Scenario: A first sync
- GIVEN an account just connected, on Wi-Fi
- WHEN the mail tab's first sync runs
- THEN about 50 messages of its inbox are listed and readable offline when it ends, and no other mailbox is listed

#### Scenario: A small account
- GIVEN an account holding 30 messages
- WHEN its first step runs
- THEN all 30 are on the phone, and the list has nothing more to load

### Requirement: The mail list ends on Load more
While a shown account that is on has more to load, the mail list SHALL end on a footer: a button, *Load more*, over a line saying about how many messages a step brings each account, or each mailbox where one steps mailbox by mailbox (*About 50 more per account*, *About 50 more per mailbox*). A Gmail account shown by one of its labels alone has more to load while that label's frontier is not all mail; any other account has more in each shown mailbox whose frontier is not all mail. A tap SHALL take one step of each such Gmail account and of each such mailbox of any other account, one after another under the sync dock, then read the list again and download the bodies left. While a step or any other pass holds the sync (a tap's, the first sync's, a pull's, the drawer's, a background run's), the button SHALL be disabled and read *Loading more*, so a second tap queues nothing, and SHALL read *Load more* again once every account's step ended. Off Wi-Fi the line SHALL add that it waits for Wi-Fi and the button SHALL be disabled. Mailboxes kept whole SHALL take no part. The junk and the trash, which no step of their account brings, SHALL take part only when their account shows one of them alone: a tap SHALL then step it by its label on Gmail (`labelIds` `SPAM` or `TRASH` with `includeSpamTrash`), and elsewhere widen it by its next chunk of headers, its bodies left to opening, until it is listed down to its account's bound. The footer SHALL hide under a search, and SHALL stand in for an empty list's empty state.

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

#### Scenario: Two mailboxes of one Microsoft account
- GIVEN a Microsoft account whose inbox and Sent are ticked, both with older mail
- WHEN *Load more* is tapped
- THEN its inbox then its Sent take a step, about 50 each

### Requirement: An account's frontier is where its steps reached
Each mail account SHALL keep a frontier, as app state beside the account: the `Date` of the oldest message its steps brought, leaving out a message dated more than a day before it was received (an import, a wrong clock), which is held and shown once the list's floor passes it; the oldest of them when every one is such. A step walking by reception (Gmail, JMAP without `sentAt`) SHALL never leave a frontier older than the oldest reception it brought, since a message it left out was received before that and may be dated after its oldest `Date`. A step over one mailbox alone SHALL move that mailbox's own frontier and never the account's. On Gmail a mailbox's frontier SHALL be the older of its own and its account's; on any other account, whose mailboxes step one by one, its own once it holds one, else its account's, and a mailbox with a frontier of its own SHALL have more to load while that one is not all mail. A step SHALL only move a frontier back; only the account's settings move it later (A window moved later frees the bodies below it), a mailbox's own frontier older than the account's new one then going. The merged list's floor SHALL be the most recent of the shown mailboxes' frontiers and of their coverage floors, a Gmail or JMAP mailbox bringing no coverage floor.

#### Scenario: An imported message
- GIVEN a step bringing messages dated from 8 to 9 October and one dated March 2019 received on 9 October
- WHEN it ends
- THEN the frontier is 8 October, the March 2019 message held and not listed above it

#### Scenario: A late delivery on a step by reception
- GIVEN a Gmail step bringing messages received from 14:00 to 18:00, one of them dated 09:00 and received at 15:00
- WHEN it ends
- THEN the frontier is 14:00, so no message received before 14:00 but dated after 09:00 is missing above it, and the 09:00 one is held and shown once the frontier passes it

#### Scenario: A step over one label
- GIVEN a Gmail account whose frontier is 1 October, its view filtered to the label Clients
- WHEN *Load more* brings Clients down to 1 September
- THEN Clients lists down to 1 September, and the merged view still ends at 1 October

#### Scenario: Sent ticked later
- GIVEN a Microsoft account whose inbox reached 1 August, its Sent ticked today and first listed down to 1 October
- WHEN *Load more* is tapped
- THEN Sent steps from 1 October, not from 1 August

### Requirement: A JMAP step is one anchored query
A JMAP step SHALL be one `Email/query` over the account with `inMailboxOtherThan` the trash, the junk and every mailbox the account's step does not take (`inMailbox` for a step over one mailbox), sorted by `sentAt` descending when the account's mail capability lists it in `emailQuerySortOptions`, else by `receivedAt`, with `calculateTotal` and `collapseThreads` false and a limit of about 50, its `Email/get` back-referenced for the summary properties, `receivedAt` and `blobId`. The account's `Email` state SHALL be read before it, as the checkpoint. A step SHALL answer the ids its query reached last, oldest first, and the next step SHALL query from the first of them with `anchorOffset` 1; a query refused with `anchorNotFound` SHALL be asked again from the next one, and once none is left SHALL page by `receivedAt` below the frontier, two days of margin above it. Each `Email` the store does not hold SHALL be downloaded by its blob, one after another on the account's session, and placed in every mailbox its `mailboxIds` names; one the store holds SHALL be passed over, a window held whole moving on to the next, a few queries at most. A body that fails SHALL leave the anchors before it, so the next step reads it again. The app SHALL keep the anchors beside the account's frontier, per account and per mailbox stepped alone, and SHALL save them only once the step landed. A JMAP account SHALL be listed account-wide, as a Gmail one is.

#### Scenario: A second step
- GIVEN a JMAP account whose first step brought its 50 newest inbox messages
- WHEN *Load more* is tapped
- THEN one query over the inbox, anchored on the oldest of them, answers the next 50, read whole

#### Scenario: The anchor deleted
- GIVEN the oldest message of the last step deleted on the server since
- WHEN the next step runs
- THEN its query is asked again from the message before it, and no message is skipped

#### Scenario: Two mailboxes
- GIVEN a JMAP message filed in the inbox and in Clients, not on the phone
- WHEN a step brings it
- THEN it is downloaded once, and both mailboxes hold it whole

## REMOVED Requirements
