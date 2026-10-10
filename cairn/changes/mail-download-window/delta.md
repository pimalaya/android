---
cairn: delta
change: mail-download-window
---

Folds into `spec/mail.md`, except the two requirements marked *offline-store.md* under MODIFIED, which fold into `spec/offline-store.md`. Fold after `background-check` and `onboarding-options-ask-on-switch`, whose "New mail notifies" the ADDED notification requirement narrows (see the proposal).

The intro of `spec/mail.md` changes too. Its first paragraph's second sentence becomes:

> The merged list is one descending scan of the sort key across the mail collections the filter lets through, down to the window of the accounts it shows, read a page at a time around the scroll position and sized by a count, so a listing never parses a date and never holds the whole store.

Its second paragraph becomes:

> A message rises off that rung by being opened, or by the body step that downloads its account's window behind the list: every message dated on or after the window's date, in every mailbox but the junk and the trash. What was fetched is filed as the item's object, as the bytes the server sent, so the item reaches full and every read after it, offline included, is a read of the store. The list shows the window, so what it shows is on the phone; the headers below it keep syncing, for search.

Its third paragraph's first sentence becomes:

> Every mailbox runs io-pimdir's sync, on a session of the pass's pool, from its floor: its first pass lists its 50 newest messages, the floor being the oldest `Date` among them, a background fill widens it a chunk of messages at a time toward the account's bound, all of its mail or the last N months on the `Date` header, and the list's footer reaches it down to a date at once.

## ADDED Requirements

### Requirement: A mail account keeps a download window
A mail account SHALL keep a window date, kept as app state beside the account and not in the store: the messages dated on or after it are the ones the phone holds whole. It SHALL be set at the end of the account's first mail sync, with nothing asked at onboarding: the floor of the inbox's first chunk (the oldest `Date` among its 50 newest); for an account listed account-wide (Gmail), one with no inbox, or one whose inbox fitted in its first chunk, the most recent floor among its mailboxes; all mail when every mailbox fitted in its first chunk; the first of the current month for an account holding no mail. An account set up again SHALL keep the window it holds. It SHALL move only when the user moves it (back from the list's footer, its date picker or the account's settings, later from the account's settings alone), and never by itself: new mail lands above it. A date the user picks or the footer offers SHALL be a day on the device's clock, the window holding the instant of its midnight there, as the list's day headers do. It SHALL never be older than the floor of the account's bound: a date picked below that floor SHALL widen the bound to the smallest choice covering it, or all mail, and say so, and narrowing the bound above the window SHALL raise the window to the new floor. All mail SHALL set both the window and the bound to all mail. A message with no date SHALL fall below every window but all mail. A mailbox kept whole SHALL have no window.

#### Scenario: A first sync
- GIVEN an account whose inbox's 50 newest messages reach back to 15 October
- WHEN its first mail sync ends
- THEN its window is 15 October, and the bodies dated since then download in every mailbox but the junk and the trash

#### Scenario: A date past the bound
- GIVEN an account bounded to the last 3 months on 10 October 2026
- WHEN 3 March 2026 is picked for its window
- THEN its window is 3 March, its bound reads the last year, and the confirmation says the sync period was widened

#### Scenario: Narrowing the bound
- GIVEN an account bounded to the last year, its window at 1 March 2026
- WHEN its bound is set to the last 3 months on 10 October 2026
- THEN its window is 1 July 2026, the bound's new floor

### Requirement: The mail list ends on a footer moving the window back
While the list's floor holds older mail back, the mail list SHALL end on a footer: a button "Load since" a date, an info line under it, and "Choose a date". The date SHALL be the first of the month of the newest stored message dated below the floor, among the shown mailboxes under the chips, so an empty month is skipped and a tap never loads nothing; where none is stored below it but a shown mailbox still has headers to list below the floor, it SHALL be the latest first of a month before the floor (a floor of 15 October offers 1 October, one of 1 October offers 1 September). The info line SHALL count the shown messages stored between the date and the floor and sum their sizes ("1,240 messages · about 85 MB"), leaving out the sizes the store does not know and then saying for how many ("size unknown for 40"); it SHALL add that older headers are still syncing while a shown mailbox has headers to list there, that the download waits for the network when there is none, and on a metered network that larger messages wait for Wi-Fi when the total passes 10 MB.

A tap SHALL set the window of every shown account that is on to the earlier of its window and the date and read the list again at once, the rows already stored showing dimmed until their bodies land; then list the band down to the date of every shown mailbox whose floor is above it, a chunk of 500 at a time as the fill does, so opening a message waits a chunk at most, stopping when a sync starts; then download the bodies the windows now take in. "Choose a date" SHALL open a date picker offering any day before the floor and "All my mail", showing the same info line for the date picked before it is confirmed, then do what a tap does with that date. The info line SHALL count only the mailboxes whose bodies download (no junk or trash unless kept whole). The button SHALL go when no stored message lies below the floor and no shown mailbox has headers left to list below it; "Choose a date" SHALL stay while a shown account is bounded, except under an empty list, which shows its empty state alone. The footer SHALL follow the chips, a role chip moving the windows of the accounts it shows, and SHALL hide under a search. With no network the tap SHALL still move the windows and show the stored rows, the band and the bodies waiting for the network.

#### Scenario: Two accounts at different windows
- GIVEN account A's window at 15 October and account B's at 3 June, both shown
- WHEN the footer's "Load since 1 October" is tapped
- THEN A's window is 1 October, B's stays 3 June, and the list reaches down to 1 October for both

#### Scenario: An empty month
- GIVEN a list whose floor is 1 October and no stored message in September but some in August
- WHEN the list's end is reached
- THEN the footer offers "Load since 1 August"

#### Scenario: Offline
- GIVEN no network and a footer offering 1 September
- WHEN it is tapped
- THEN the window moves, the stored rows down to 1 September show dimmed, and the info line says the download waits for the network

### Requirement: The unread badge counts the windows
The mail badge in the bottom bar SHALL count the unread messages dated on or after each account's window, in the mailboxes the mail filter shows, whatever the chips and the search; a mailbox kept whole SHALL count under its account's window.

#### Scenario: Years of unread mail
- GIVEN an account whose window is 1 September and whose fill listed 4,000 unread messages from earlier years
- WHEN the badge is drawn
- THEN it counts only the unread messages dated since 1 September

### Requirement: A row not on the phone is dimmed
A message row whose body the store does not hold SHALL be drawn dimmed. Within its account's window, outside the junk and the trash, it is on its way: dimmed alone, or saying "Downloads on Wi-Fi" while a metered network holds it back. Below the window, or in a junk or trash mailbox not kept whole, it SHALL say "Not downloaded". A row whose body is held SHALL never be dimmed, wherever it falls.

#### Scenario: A search beyond the window
- GIVEN an account whose window is 1 September and a stored header from March, never opened
- WHEN its sender is searched
- THEN the March row is found, dimmed, saying "Not downloaded"

#### Scenario: A large message on mobile data
- GIVEN a 3 MB message from yesterday within the window, on a metered network
- WHEN the list is drawn
- THEN its row is dimmed and says "Downloads on Wi-Fi"

### Requirement: A message not on the phone says so offline
Opening a message whose body the store does not hold, with no network, SHALL show in the reader that the message is not on the phone yet, and SHALL NOT reach for the network nor show a blank page: that it will download once back online when it falls within its account's window outside the junk and the trash, and to open it again once online otherwise.

#### Scenario: Opened offline beyond the window
- GIVEN no network and a search result dated before its account's window, never opened
- WHEN it is opened
- THEN the reader says it is not on the phone and to open it again once online

### Requirement: A notification reaches no lower than the window
A background run SHALL notify only the messages dated on or after their account's window and after the newest message it already notified for that account, kept no later than the run so a message dated in the future cannot silence later ones, so headers an in-app fill lists between two runs never notify. An account with nothing notified yet (notifications just turned on, or an install upgraded) SHALL take the newest unread inbox message already there as that mark, so none of it notifies.

#### Scenario: A fill between two runs
- GIVEN an account notifying new mail, whose last run notified a message of this morning
- WHEN the app's fill lists unread inbox messages from last year, then a run syncs a new message
- THEN only the new message notifies

### Requirement: An account's settings show its window
A mail account's settings SHALL show its window ("Mail on this phone since 1 September", or "All my mail"), and the row SHALL open the footer's date picker over the account alone and the mailboxes the mail filter shows, offering any day up to today. A day before the window SHALL move it back as the footer does. A day after it SHALL ask first, saying the messages before it leave the phone while their headers stay for search; confirmed, the window SHALL move to it and the bodies of the account's messages dated below it SHALL be freed (A window moved later frees the bodies below it).

#### Scenario: Moving the window back from settings
- GIVEN an account whose window is 1 September
- WHEN 1 June is picked from its settings
- THEN its window is 1 June, and the bodies dated since then download

#### Scenario: Moving the window later from settings
- GIVEN an account whose window is 1 June
- WHEN 1 September is picked from its settings and the question confirmed
- THEN its window is 1 September, and the bodies of its messages before it are freed, their rows still found by a search

### Requirement: A window moved later frees the bodies below it
Moving an account's window later SHALL free the bodies of its messages dated below the new window, through pimdir's owner release (STORAGE section 11.4: the bindings' bases, then the items back to `Meta`, then the counts, in one transaction, then the collector), each message's headers, flags and bindings kept. A mailbox kept whole SHALL keep all of it, and so SHALL a message the store still needs the body of: a conflict, a pending create, a local edit, one a source of its mailbox does not bind yet. A message freed and opened again SHALL be fetched, as one never opened is.

#### Scenario: An archive kept whole
- GIVEN an account whose Archive is kept whole
- WHEN its window is moved later
- THEN the Archive keeps every body, and the other mailboxes free theirs below the new window

### Requirement: An account set up before the window takes one
An account whose first mail sync was paid before windows existed SHALL take a window once, when the app starts: the floor of its bound where it downloaded bodies in the background or kept all mail, its bodies being mostly held; else the floor of its inbox's first chunk, the oldest `Date` among the 50 newest stored inbox messages, so nothing downloads by surprise. The offline policy and metered setting it carried SHALL then be forgotten.

#### Scenario: Bodies on open
- GIVEN an account set to download bodies on open, its inbox holding 3,000 stored messages
- WHEN the app starts with this version
- THEN its window is the date of its 50th newest inbox message, and only the bodies since then download

## MODIFIED Requirements

### Requirement: The mail list narrows what it shows
The mail list SHALL list the stored messages of every mailbox the filter lets through, down to the list's floor (The merged list reaches down to its mailboxes' floor), and SHALL offer a search over the sender and the subject, the role chips, an unread chip and an attachments chip, all answered by the store: the chips within the floor, the search over every stored message. None of these SHALL change what syncs, which the filter alone decides, nor what downloads, which the windows decide.

#### Scenario: Unread only
- GIVEN read and unread mail in two accounts
- WHEN the unread chip is on
- THEN the list shows both accounts' unread messages down to its floor and nothing else

#### Scenario: Searching past the window
- GIVEN an account whose window is 1 September and a stored header from March
- WHEN its sender is searched
- THEN the March message is found

### Requirement: The mail list selects
A long press on a message SHALL start a selection holding it, and while one runs a tap SHALL add or remove a message rather than open it. The bar SHALL then carry the count, read or unread, star or unstar, delete after asking, and select-all, each over the whole selection, a toggle going the way that changes something. The selection SHALL be keyed by store id, and select-all SHALL select what the list's query lets through, down to its floor or every match of a search, read whole only when the bar acts on it. Back SHALL clear the selection.

#### Scenario: Starring three messages
- GIVEN three messages, one of them starred
- WHEN they are selected and the bar's star is pressed
- THEN all three are starred

#### Scenario: Selecting all
- GIVEN a list whose floor is 1 September over an account storing headers back to 2020
- WHEN select-all is pressed
- THEN the messages dated since 1 September are selected, and none older

### Requirement: An opened message is stored and read back
The app SHALL store an opened message as its item's object, as the bytes the server sent, and SHALL render a later open from the store without reaching the network, whether the body was stored by an open or by the body step. Fetching SHALL happen only for a message the store does not hold, whatever its date: a message opened below its account's window SHALL be fetched and kept like any other.

#### Scenario: The same message twice
- GIVEN a message opened once
- WHEN it is opened again
- THEN it renders from the store, with no request

#### Scenario: A message never opened
- GIVEN a message the store holds at meta
- WHEN it is opened
- THEN the message is fetched, stored, and rendered from what was stored

#### Scenario: Offline
- GIVEN a stored message and no network
- WHEN it is opened
- THEN it renders

#### Scenario: Downloaded in the background
- GIVEN a message within its account's window whose body the body step stored
- WHEN it is opened with no network
- THEN it renders

#### Scenario: Opened below the window
- GIVEN a search result dated before its account's window
- WHEN it is opened with a network
- THEN it is fetched and stored, and its row is no longer dimmed

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within its scope (its floor, within the account's bound), newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph `/messages` page, 100 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`, or for a delta's member the store does not bind, from that `$select` read by id; Gmail from its metadata read; JMAP from `Email/get`'s summary properties. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within its scope; mail outside it SHALL never be deleted by a sync. The chunks, the fill and the list's footer (A mailbox is listed a chunk at a time, Older mail fills in behind, The mail list ends on a footer moving the window back) SHALL bring a mailbox whole within the account's bound.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 100k messages and an empty store
- WHEN it is synced
- THEN its newest chunk is listed with its subject, sender and date before the first sync ends
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
An account's settings SHALL offer to sync all of its mail or the last 1, 3, 6, 12 or 24 months, the floor being the first day of the month that many months back, on the `Date` header (a message with no usable date in every scope). The bound is how far headers are kept, and so how far a search reaches and how far the window can go back. A mailbox's floor SHALL never go below its bound: its account's, or none for a mailbox kept whole. A chunk reaching past the bound stops at it, and a mailbox whose floor is the bound is whole. A provider's received-date filter SHALL only narrow a listing, two days below the floor (IMAP `SENTSINCE` one day below). Widening the bound SHALL have the fill carry on below the old floor, chunk by chunk; a window date picked below the bound SHALL widen it (A mail account keeps a download window). Narrowing it SHALL collect the stored messages dated below the new floor that owe nothing to the server, their mailboxes keeping them there, except in a mailbox kept whole, and SHALL raise a window left below the new floor to it.

#### Scenario: Narrowing to a year
- GIVEN an account syncing all of its mail, its window at all mail
- WHEN its bound is set to the last year
- THEN the messages older than that leave the store, except one with a change not pushed yet or one in a mailbox kept whole
- AND its window is raised to the bound's new floor
- AND nothing is deleted on the server

#### Scenario: Widening again
- GIVEN that account
- WHEN its bound is set back to all mail and the app stays open on an unmetered network
- THEN the older messages are listed again, a chunk at a time, and its window stays where it is

### Requirement: The mail list loads lazily
The mail list SHALL hold only the rows near the scroll position, read a page at a time from the store and the far pages evicted, sized by a count of what the filter, the chips, the search and the list's floor let through, with a placeholder row while a page loads. It SHALL place its day headers from one count per day, without loading rows. The messages waiting to go out SHALL stay on top, outside the paged query. Search, the chips, the floor and the unread badge SHALL be conditions of the store's query, a floor or a window applied by the canonical statements' own bound on the sort key rather than around them; search SHALL cover every stored message, and the badge each shown account's window. A list showing the store SHALL redraw as a pass's pages land.

#### Scenario: Scrolling to old mail
- GIVEN 100k stored messages in one mailbox listed whole, its account's window at all mail
- WHEN the list is flung to its end
- THEN the oldest message is shown, and memory holds a bounded number of rows

#### Scenario: Searching old mail
- GIVEN a message from three years ago, stored
- WHEN its sender is searched with no network
- THEN it is found

### Requirement: A mailbox is listed a chunk at a time
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be the oldest `Date` among the 50 newest the source names (the last 50 UIDs `UID SEARCH` finds, Graph's `$top` ordered by `sentDateTime`, Gmail's `messages.list`, JMAP's `Email/query` by `receivedAt`), or no floor when it holds fewer, within the account's bound. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the oldest `Date` among the newest messages dated before it, and reaching a date (the list's footer) SHALL take that date, within the bound; either SHALL list only the band it lacks, on every backend: no checkpoint is bound to a scope.

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
- WHEN the fill widens it
- THEN the next 500 below the floor are listed, the band alone

#### Scenario: Reaching a date
- GIVEN a mailbox listed down to 15 October
- WHEN the list's footer loads since 1 September
- THEN the messages dated from 1 September to 15 October are listed, the band alone, and its floor is 1 September

### Requirement: A Graph mailbox keeps one unfiltered delta link
A Graph mailbox SHALL keep one message delta link made with no filter, selecting the id, `sentDateTime`, `isRead` and `flag`, every request asking 1,000 a page (`Prefer: odata.maxpagesize`), so its checkpoint is bound to no scope. Its mail SHALL be listed by band: a first chunk, the fill's widening and the footer's reach to a date SHALL list only the band they lack by `/messages` filtered on `sentDateTime` (from the floor, below the ceiling: the `Date` header, exactly), newest first, up to 1,000 a page with the summary `$select`, a page resuming below the oldest `Date` the pages before reached rather than by `$skip`. A first chunk SHALL land without waiting on the delta link: the round a later pass opens over the scope the store covers SHALL make it, walking the delta's first pass a page at a time, listing the members in scope by id and markers, and closing with the link, so what changed or went between the chunk and the link is read there. A delta SHALL apply a removal whatever the date, drop a change to a message dated out of the scope, and list one in scope by id and markers alone: a message the store binds keeps its summary, any other one is read by id with the summary `$select`, 20 to a `$batch`. An expired link (410), or one made under a filter by an earlier version, SHALL open the round that makes a new one, relisting no band.

#### Scenario: The first chunk does not wait on the link
- GIVEN a Graph inbox of 50,000 messages never listed
- WHEN the first sync fetches it
- THEN its 50 newest are listed by `/messages` and no delta is asked for
- AND the next pass names the 50,000 by id once and keeps the link

#### Scenario: Widening relists nothing
- GIVEN a Graph inbox listed down to its 50th newest message, its delta link made
- WHEN the fill widens it twice and the list's footer reaches back a month below that
- THEN each time only the band it lacked is listed, and the link is kept

#### Scenario: A change out of scope
- GIVEN a Graph inbox listed down to September
- WHEN a message from March is marked read elsewhere
- THEN the next delta reports it, and nothing is stored

#### Scenario: A message gone before the link
- GIVEN a Graph inbox whose first chunk is listed, its link not made yet
- WHEN a message of the chunk is deleted elsewhere
- THEN the pass that makes the link removes it

### Requirement: The merged list reaches down to its mailboxes' floor
The merged list SHALL show no message older than its floor: the most recent of the window of every account it shows a mailbox of that is not kept whole, and of the coverage floor of every mailbox it shows, so it stays continuous by date across accounts and no mailbox's older mail is listed while another's of the same days is not stored yet. A mailbox kept whole SHALL bring no window, its own floor being its coverage floor, so shown alone it lists all it stores. The messages waiting to go out SHALL stay on top, outside the floor. The count under the title, the day headers, select-all and the chips SHALL follow the floor; a search SHALL ignore it and cover every stored message. While the floor holds older mail back the list SHALL end on its footer (The mail list ends on a footer moving the window back); reaching the list's end SHALL widen nothing.

#### Scenario: Two accounts at different windows
- GIVEN account A's window at 15 October and account B's at 3 June, both listed below their windows
- WHEN both are shown
- THEN the list stops at 15 October for both, and B shown alone lists down to 3 June

#### Scenario: Two mailboxes at different depths
- GIVEN an inbox listed down to Tuesday and a sent mailbox down to the week before, their account's window older than both
- WHEN the list is scrolled to its end
- THEN it ends at Tuesday on its footer, and reaching the end lists nothing

#### Scenario: A mailbox kept whole
- GIVEN an Archive kept whole and listed whole, its account's window at 1 September
- WHEN the Archive is shown alone
- THEN every stored message of it is listed

### Requirement: Older mail fills in behind
After a mail tab's first sync, and on every return to the app or pass after it, every mailbox SHALL widen 500 messages at a time toward its account's bound, whatever the window, with no dialog, the inbox and the sent mail first, a mailbox never listed before one that only lacks older mail, and among them the one holding the most recent floor; a step SHALL widen the next mailboxes in that order side by side, as many of an account as its pool runs. A step SHALL list nothing while the body step has bodies left that it may download now, so a window is readable before the older headers list. The fill SHALL run only while the app is in the foreground, on a network that is not metered, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers.

#### Scenario: Leaving the app
- GIVEN a fill under way
- WHEN the app goes to the background
- THEN no further chunk is listed, and the next return resumes below the floors reached

#### Scenario: A metered network
- GIVEN a phone on mobile data
- WHEN the first sync ends
- THEN only the first chunks are stored, with the window's bodies within the size cap, until an unmetered network is back

#### Scenario: Bodies first
- GIVEN an account whose first sync just ended on Wi-Fi
- WHEN the app stays open
- THEN the window's bodies download before any older header is listed

### Requirement: An account's mailboxes sync side by side
A mail pass, the first sync, the band a footer tap lists and the background fill SHALL run an account's mailboxes concurrently on a pool of sessions, four for Graph and JMAP, three for IMAP, two for Gmail, each worker on a session of its own that no other worker uses while it runs, the workers taking the mailboxes in the pass's order. Every storage load, lookup and write SHALL be answered by one writer at a time, so only the network overlaps. A mailbox that fails SHALL leave the others running, the pass reporting the first failure. The first sync SHALL end once every mailbox's first chunk has landed, its strip saying how many of the account's mailboxes have.

#### Scenario: A first sync of twelve mailboxes on Graph
- GIVEN a Graph account of twelve mailboxes never listed
- WHEN its first sync runs
- THEN four mailboxes are listed at once, the inbox begun first, and the first sync ends once all twelve chunks have landed

#### Scenario: One mailbox refused
- GIVEN a pass over five mailboxes, one of which the server refuses
- WHEN the pass runs
- THEN the other four are stored, and the pass reports the refusal

#### Scenario: Two pages landing together
- GIVEN two mailboxes whose pages arrive at the same moment
- WHEN both are written
- THEN one write lands whole before the other begins

### Requirement: A mailbox can be downloaded whole
A mailbox's row on the mail filter page SHALL offer "Download", which after a confirmation keeps that mailbox whole whatever its account's window: its scope SHALL be all of its mail, past the account's bound, the fill SHALL widen it below its floor, narrowing the account's bound SHALL NOT collect it, and every listed body SHALL be downloaded, a junk or trash role notwithstanding, on a metered network within the size cap. The row SHALL then say it is kept whole and offer "Stop", which returns it to the account's bound and window, what is stored staying until the bound is narrowed again.

#### Scenario: An archive kept whole
- GIVEN an account bounded to the last year, its window at 1 September
- WHEN its Archive is downloaded from the filter page
- THEN every message of the Archive is listed and its body stored, and the account's other mailboxes keep their year, their bodies downloaded since 1 September

#### Scenario: The trash
- GIVEN an account's trash, whose bodies the window leaves out
- WHEN it is downloaded from the filter page
- THEN every body of it is stored

### Requirement: Bodies download behind the list
For each account that is on, the app SHALL raise to `Full`, through the engine's upgrade, every listed message dated on or after the account's window in every mailbox but the junk and the trash, and every listed message of a mailbox kept whole, after each pass, each fill step and each move of a window while the app is open: newest first, the mailboxes the mail filter shows before the account's others, which download too, at most 24 bodies an account a step, side by side on the account's pool. A message with no date SHALL be downloaded only under a window of all mail or in a mailbox kept whole, and a pending create never. A body the store already holds under the same link id SHALL be linked rather than fetched. The body SHALL be stored as the bytes the server sent, its base moved to it, its summary and sort key kept, its attachment mark restated from its parts. On a metered network only bodies up to 256 KB SHALL download, a message of unknown size counting as larger unless its attachment mark says it carries none; the others SHALL wait for an unmetered network. The step SHALL run only while the app is in the foreground, no other sync runs and a network is there; it SHALL never be periodic work, and SHALL stop when the user starts a sync or leaves the app. A body the server fails to hand over SHALL be left below `Full` and tried by a later run; an account whose download fails as a whole (no session, an expired token) SHALL leave the run. Gmail body reads SHALL count against the account's per-minute quota units like any other read, with no pacing of their own.

#### Scenario: Within the window only
- GIVEN an account whose window is 1 September, holding a message from yesterday and one from March
- WHEN the body step runs
- THEN yesterday's body is downloaded and stored, the March one stays a summary

#### Scenario: Junk and trash
- GIVEN a message from yesterday in the junk, its mailbox not kept whole
- WHEN the body step runs
- THEN its body is not downloaded

#### Scenario: A metered network
- GIVEN an account on mobile data whose window holds a 40 KB message, a 3 MB one, and one of unknown size marked as carrying an attachment
- WHEN a pass ends
- THEN the 40 KB body is downloaded, and the other two wait for an unmetered network

#### Scenario: The same message under two labels
- GIVEN a Gmail message under two labels, its body downloaded in one
- WHEN the body step reaches the other
- THEN the body is linked from the store and not read again

### Requirement: The drawer shows bodies left to download
While the body step runs for an account, the account's pill in the drawer SHALL say how many bodies it has left to download, in place of when it last synced, and SHALL go back to that once the step ends.

#### Scenario: A window moved back
- GIVEN an account whose window was just moved back a month
- WHEN the drawer is opened while its bodies download
- THEN its pill counts the messages left, falling as they land

### Requirement: A list opens on a large title
*offline-store.md*

Every list screen SHALL open on a large title naming what it shows over a supporting line counting it, scrolling with the rows: the mail list's line counts what the list shows down to its floor, not every stored message. The bar SHALL carry the same title only once the large one has scrolled out of sight. The list's add button SHALL be an extended one, a pencil on mail, a person-plus on contacts and a plus on the agenda, shrinking to its square glyph while the list scrolls down and growing its label back when it scrolls up.

#### Scenario: Scrolling the mail list
- GIVEN the mail list at its top
- WHEN it is scrolled past its large title
- THEN the bar shows the title, and the add button shrinks to its glyph

#### Scenario: The mail count
- GIVEN 1,240 messages within the windows and 30,000 stored headers below them
- WHEN the mail list is shown
- THEN its line counts 1,240

### Requirement: An empty list says so below its header
*offline-store.md*

A list with nothing to show SHALL centre its empty state in the space its header leaves below it, clear of the search, the chips and the week card. A mail list whose floor holds older mail back SHALL show its footer in that place instead.

#### Scenario: An empty day
- GIVEN a day with no entry picked in the week card
- WHEN the agenda renders
- THEN the empty state sits under the week card, not behind it

#### Scenario: An empty window
- GIVEN an account whose window holds no message, older mail stored below it
- WHEN the mail list renders
- THEN its footer shows where the empty state would

## REMOVED Requirements

### Requirement: An account chooses which bodies it keeps offline
Replaced by the window (A mail account keeps a download window, Bodies download behind the list): no offline policy, and no metered switch, the size cap taking its place.
