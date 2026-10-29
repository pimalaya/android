---
cairn: spec
capability: offline-store
status: current
---

# Offline store

The app keeps one pimdir store for every account and every domain: `collections.kind` carries the media type (`message/rfc822`, `text/vcard`, `text/calendar`) and `collections.account` groups by account, so the merged view's two filter axes are columns rather than three databases. Bodies live in a content-addressed blob directory beside the database, referenced by hash and refcounted.

The schema is io-pimdir's, handed to Java over JNI rather than transcribed, and executed on the SQLite the app bundles (3.49, behind the platform's own binding), since the platform's is too old for the schema below Android 14. The crate is taken without its `client` feature: compiling a second engine into every ABI would work against the app's first design goal.

Every collection is reconciled by io-pimdir's sync engine, so every collection carries bindings, staged writes and conflicts. A contacts collection is reconciled against two sources, the server and the phone, which the store holds as one item with one `bindings` row per source; mail and calendar have the one source each and are otherwise the same. Nothing replaces a collection's contents: a refresh reconciles it, which is what lets a write staged before it survive it.

### Requirement: The engine is io-pimdir's
The bridge SHALL run io-pimdir's sync, upgrade and mutate coroutines, and the app SHALL NOT depend on io-replica.

#### Scenario: A sync pass
- GIVEN an address book with a staged edit
- WHEN the app syncs it
- THEN `PimdirSync` derives the push and the driver services its yields

### Requirement: The store's shape follows the canonical schema
The store SHALL reconcile its own shape against the canonical pimdir DDL on open: creating every table, index and trigger the schema declares that it lacks, adding every column it lacks, dropping every column the schema no longer declares, and rebuilding an index or a trigger whose text has moved. It SHALL NOT carry a transcribed list of the columns a draft folded in or out, nor of the indexes one reshaped.

#### Scenario: A column the draft folded in
- GIVEN a store created before `bindings.conflict_object` was declared
- WHEN the app opens it
- THEN the column is added and the store opens

#### Scenario: A column the draft folded out
- GIVEN a store carrying `bindings.ambiguous_handles`, which the schema no longer declares
- WHEN the app opens it
- THEN the column is dropped, or kept with a log where SQLite refuses to drop it

#### Scenario: A table the draft added
- GIVEN a store created before the summary tables were declared
- WHEN the app opens it
- THEN they are created and the store opens

#### Scenario: An index whose columns moved
- GIVEN a store holding an index of the canonical name over other columns
- WHEN the app opens it
- THEN the index is dropped and recreated from the canonical DDL

#### Scenario: A shape the parse could not read
- GIVEN a table the canonical DDL yielded no column for
- WHEN the app reconciles
- THEN it changes nothing about that table, rather than reading the empty answer as "every column is stale"

### Requirement: A schema version is migrated, not recreated
The store SHALL reach a newer schema version by running every canonical migration above its own, in order, stamping `store_meta.version` with each version reached, as io-pimdir's own runner does. It SHALL recreate the store only on a downgrade, whose newer shape no migration reads back.

#### Scenario: A store one version behind
- GIVEN a store at version 1 holding a staged edit and a queued message
- WHEN an app shipping version 2 opens it
- THEN migration 2 runs, `store_meta.version` reads 2, and the edit and the message are still there

### Requirement: An item carries the standard's summary
The store SHALL record what a reader lists an item from in its kind's summary table and the people it names in `item_address`, as pimdir STORAGE Annex A defines them, written through the canonical statements. It SHALL NOT keep a summary convention of its own in the item row, and SHALL derive one through io-pimdir wherever the body is on hand: a calendar object listed from its server or the phone, or staged by the app, SHALL carry the summary and sort key its body derives.

#### Scenario: A card is written
- GIVEN a vCard with a `FN`, a `UID` and two `EMAIL` properties
- WHEN the app writes it
- THEN `contact_summary` holds its name, uid, kind and org, and `item_address` holds both emails under the `email` role in document order

#### Scenario: A message listed but not fetched
- GIVEN an IMAP envelope and no body
- WHEN the mail mirror writes the row
- THEN `mail_summary` holds the same fields the body's derivation would, built from the envelope

#### Scenario: A to-do listed
- GIVEN a CalDAV calendar holding a VTODO carrying `CATEGORIES:waiting,@home` and `PRIORITY:2`
- WHEN it syncs
- THEN `task_summary` holds its summary, its priority and both categories

#### Scenario: A summary the writer did not restate
- GIVEN an item whose summary is stored
- WHEN a write carries none
- THEN the stored summary is kept, on the same terms as the sort key

### Requirement: A placement no source binds is offered under its provisional handle
The store SHALL hand out `U+0001` followed by the link id as the handle of a placement the source does not bind (SYNC §2), and SHALL resolve that handle back to the identity it spells. A push SHALL name the resource by that identity, never by the provisional handle, and the binding SHALL take the name the server filed the create by, which a server may choose itself.

#### Scenario: A card staged for a book the phone has never seen
- GIVEN an item with no binding for the phone spoke
- WHEN that spoke loads the collection
- THEN the placement carries the provisional handle, and a row read under it resolves to the item

#### Scenario: The create is pushed
- GIVEN a staged create under its provisional handle
- WHEN the push offers it to the server
- THEN the resource is named after the card, and the binding takes whatever handle the push assigns

#### Scenario: The server files the create under a name of its own
- GIVEN a card created on the phone, pushed to Google's CardDAV, which files it under its own name and says so in `Location`
- WHEN the card is deleted on the phone
- THEN the next pass deletes the resource Google named, and the card is not listed back

### Requirement: One identity under two handles
A write resolving an existing `(collection, link_id, source)` binding to a different handle SHALL be refused, unless the same batch supersedes the handle it holds. The store SHALL NOT record the incoming handle in the bound one's place, and SHALL NOT freeze the item.

#### Scenario: A source reports one identity twice
- GIVEN a binding bound to `v1.vcf`
- WHEN an upsert of the same link id arrives under `v2.vcf` with nothing superseding `v1.vcf`
- THEN the write is refused and the binding keeps `v1.vcf`

#### Scenario: A handle-space rebuild
- GIVEN a batch dropping `v1.vcf` as superseded
- WHEN the same batch upserts the link id under `v2.vcf`
- THEN the binding is replaced and the item stays

### Requirement: A write batch applies in order
The store SHALL apply a write batch op by op in the order the engine wrote it (pimdir SYNC §10), as io-pimdir's own store does, and SHALL NOT hold drops back or cancel one against an upsert of the same handle. Only the stamps follow the batch's upserts, and an item's fate is settled from the whole batch: an item the batch created and left unbound is not stored, and a mail create a superseded drop released goes unless an upsert of the batch carried it on. An upsert naming no identity on a handle the batch dropped earlier continues the identity that handle held.

#### Scenario: A withdrawn pending create
- GIVEN a pending create with no base
- WHEN a removal writes its tombstone then a drop of its handle in one batch
- THEN the binding is gone and the item retained, with no second write

#### Scenario: A drop then an upsert of one handle
- GIVEN a bound item
- WHEN one batch drops its handle then upserts it
- THEN the item is present and bound

### Requirement: A rebuilt handle is not a removal
The store SHALL read a `rekeyed` drop as this row going and the item staying, on the same terms as a `superseded` one.

#### Scenario: A rebuilt spine
- GIVEN a collection whose handle space the remote renumbered
- WHEN the engine drops every old handle as `rekeyed` and upserts the new ones
- THEN no item is retired and no delete propagates to another source

### Requirement: A refresh keeps the bodies it does not restate
A write carrying no body SHALL keep the object the item already holds, and an item's level SHALL follow what it holds: meta with no body, full with one. A sync write of a message SHALL NOT take away a body the row holds: an upsert carrying no object at a lower level than the row's, neither a staged removal nor a conflict, SHALL keep the row's body and level, and the binding's base when it was that body, whatever the sync loaded before the body was filed (a reader opening the message, a download landing it). Only an explicit release (a narrowed window, a cross-linked body, a purge) SHALL free a message's body.

#### Scenario: A sync after a read
- GIVEN a message whose body was stored by opening it
- WHEN the mailbox is refreshed
- THEN the stored body survives the refresh

#### Scenario: A read during a sync
- GIVEN a pass that loaded a new message at meta and is pushing over the network
- WHEN the message is opened, its body stored, and the pass then writes it with new markers
- THEN the message keeps its body at full, the markers written

#### Scenario: An envelope with no body
- GIVEN a message the sync has just stored
- WHEN its row is read
- THEN it stands at meta

### Requirement: An action is a local write
Every action the reader takes in any domain SHALL be applied to the store alone and SHALL succeed with no network. Only a sync pass and the fetch of a message body SHALL reach a server.

#### Scenario: A marker written with the radio off
- GIVEN no network
- WHEN the reader marks a message read
- THEN the store records it, the list reflects it, and nothing is reported as failed

#### Scenario: An entry edited with the radio off
- GIVEN no network
- WHEN a calendar entry is saved
- THEN the agenda shows the edit and the push waits for the next sync

#### Scenario: The push is refused later
- GIVEN a staged write the server rejects
- WHEN the sync pushes it
- THEN the sync reports the refusal, the write staying staged

### Requirement: A pass asks what changed
Every domain SHALL enumerate a collection from the cursor its last pass stored, and SHALL report the round as incomplete so nothing it did not mention is retired. A collection with no cursor, or one whose cursor the server rejects, SHALL be enumerated whole. A body SHALL be read only for a member the merge asked about.

#### Scenario: A calendar nothing touched
- GIVEN a calendar synced once
- WHEN it is synced again with nothing changed
- THEN one REPORT carries the answer and no event body is read

#### Scenario: A mailbox nothing touched
- GIVEN a mailbox synced once against a QRESYNC server
- WHEN it is synced again with nothing changed
- THEN the select carries the answer and no envelope is fetched

#### Scenario: One member changed
- GIVEN a collection of five hundred members, one of them edited remotely
- WHEN it is synced
- THEN one body is read

#### Scenario: A cursor the server rejects
- GIVEN a stored cursor the server no longer accepts
- WHEN the collection is enumerated
- THEN the round falls back to a complete one and stores a fresh cursor

### Requirement: A pass opens its connections once
A sync pass SHALL open its connections when it starts and close them when it ends, and every verb it runs SHALL use them. It SHALL NOT open a connection per verb. A fan-out SHALL give each worker its own, a connection serving one caller at a time.

#### Scenario: A mail pass with changes to push
- GIVEN an account with three staged markers
- WHEN the mailbox is synced
- THEN one session carries the walk and all three writes

#### Scenario: A calendar pass
- GIVEN an account with three calendars
- WHEN the agenda is refreshed
- THEN one transport carries the calendar listing, every event listing and every write

#### Scenario: A fan-out
- GIVEN a push the contacts driver runs over several workers
- WHEN it runs
- THEN each worker has its own connection, none of them shared, and the round costs as many as there are workers

### Requirement: A mail session outlives the call that opened it
The bridge SHALL hold an IMAP session across native calls, connected and authenticated once, addressed by a handle the caller keeps and frees. Nothing on the bridge side SHALL hold a JNI reference between calls: the caller owns the transport and passes it back on every call. A run of commands on one mailbox SHALL select it once.

#### Scenario: A second command on one session
- GIVEN an open session
- WHEN a second verb runs on it
- THEN it sends its command without a greeting or an authentication

#### Scenario: A handle the caller has freed
- GIVEN a session that was closed
- WHEN a verb names its handle
- THEN it is refused rather than followed

### Requirement: A connection the server dropped is reopened once
An idempotent verb failing on a held session SHALL reopen it and run once more, and SHALL report the failure only if that fails too. A server idle timeout, a rebound NAT and a walk from wifi to cellular all end a connection under the app, so a held one is a hint and never a promise.

Submitting a message SHALL NOT be retried: a submission whose reply was lost is indistinguishable from one that never went, and running it again would send the message twice. Its failure SHALL leave the message in the outbox.

#### Scenario: An idle timeout
- GIVEN a session the server has since closed
- WHEN the next marker is written on it
- THEN the session is reopened and the write succeeds

#### Scenario: The reopen fails too
- GIVEN no network at all
- WHEN a verb runs on a held session
- THEN the failure is reported rather than retried forever

#### Scenario: A submission that failed
- GIVEN a server that accepted a message and then dropped the connection
- WHEN the drain reports it
- THEN nothing is sent a second time

### Requirement: A sync says what it is working on, in every domain
A running sync SHALL show, docked on top of the bottom navigation of every list the bottom bar shows, so it shows whichever list is open and however far it is scrolled, a dock naming what the pass is on: the account in a first line (*Starting the sync* before the pass reaches one), then a second line naming the engine's current step (*Downloading 42 messages*), after the domain where the pass runs several (*Syncing mail · Downloading 42 messages*) and the domain alone before a step is named, the count of accounts when there are several (*1 of 2*), over one thin bar on the dock's top edge. Showing, updating or hiding the dock SHALL move neither the app bar, nor the list's large title, search field, chips or rows; the extended add button SHALL rise above it while it shows. The three domains SHALL report alike, so a wait reads the same whichever one is being synced. The dock SHALL hide when nothing runs and SHALL NOT show on screens other than the lists. The app bar SHALL carry no part of it.

The bar SHALL be one progress over the whole pass, every account and every domain it runs, and SHALL never move back while the pass runs. Before it starts, the pass SHALL be sized in sections, one per domain and account, each weighing the collections stored for it, one at least, an account's mail step weighing one; a section SHALL fill its weight however many collections its listing turns up, and sections MAY fill at different speeds. Within a section, the pass SHALL tell how many collections it will run before the first one runs, and each collection SHALL fill its share as it lands; within it, a step moving items batch by batch, the members a listing did not carry being read or the contacts and events being written to the phone, SHALL fill part of that share, the download the first three fifths and the phone the rest where the domain has a phone mirror, the download all of it for mail; a calendar's steps only while that calendar runs alone; a mail step its bodies (A mail step counts the bodies it brings). The bar SHALL run indeterminate only before anything is counted, the dock then saying the sync is starting, and SHALL keep the determinate bar's track and height.

The dock SHALL NOT block the app: the lists, the reader and the composer stay usable while it runs, the lists refreshing when the pass ends. One pass SHALL run at a time, a pull or the drawer's sync asked for meanwhile doing nothing, and an account SHALL NOT be deleted while a pass runs. The dock's lines SHALL never be empty: they SHALL open saying the sync is starting, and SHALL follow the pass to the next account, domain and step once something is counted.

#### Scenario: A pass over every domain
- GIVEN the drawer's sync
- WHEN it moves from contacts to mail to calendars
- THEN the dock's second line starts with *Syncing contacts*, then *Syncing mail*, then *Syncing calendars*
- AND one bar fills once across the three

#### Scenario: A pass over several accounts
- GIVEN two mail accounts
- WHEN the mail pass moves from the first to the second
- THEN the dock's first line names the first account then the second, its count reads *1 of 2* then *2 of 2*, and the bar carries on from where the first left it

#### Scenario: The first frame
- GIVEN a sync the user has just asked for
- WHEN the dock shows, before any round trip
- THEN it says the sync is starting, over an indeterminate bar the size of the counting one

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
- THEN the dock shows above the bottom bar, just under the button, naming the account and *Loading older mail*

#### Scenario: Nothing moves
- GIVEN the mail list at its top, its large title, search field and chips shown
- WHEN a pass starts, counts and ends
- THEN the dock shows and goes, the large title, the search field, the chips and the rows never moving, the add button rising and returning

#### Scenario: Reading while it runs
- GIVEN a sync running
- WHEN a message is opened, or a sync pulled for
- THEN the message opens, and no second pass starts

### Requirement: The sync dock opens onto the pass
Tapping the sync dock SHALL open a sheet showing the pass's overall progress, one row per account of the pass saying whether it is running (with its current line), next or done, and the pass's recent lines, newest first, each with its time. The lines SHALL be kept in memory for the pass under way only, at most the last 50, and dropped when the next pass starts. The sheet SHALL close on request and when the pass ends.

#### Scenario: Two accounts
- GIVEN a pass over two mail accounts, the first running
- WHEN the dock is tapped
- THEN the sheet shows the first account running with its line, the second as next, and the lines so far

### Requirement: The drawer's sync covers every domain
The drawer's sync SHALL reconcile contacts, then mail, then calendars, and SHALL report one failure at most, the contacts one first.

#### Scenario: A marker staged on the phone
- GIVEN a message flagged in the reader
- WHEN the drawer's sync runs
- THEN the flag is pushed to the server

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

### Requirement: A list opens on a large title
Every list screen SHALL open on a large title naming what it shows over a supporting line counting it, scrolling with the rows. The bar SHALL carry the same title only once the large one has scrolled out of sight. The list's add button SHALL be an extended one, a pencil on mail, a person-plus on contacts and a plus on the agenda, shrinking to its square glyph while the list scrolls down and growing its label back when it scrolls up.

#### Scenario: Scrolling the mail list
- GIVEN the mail list at its top
- WHEN it is scrolled past its large title
- THEN the bar shows the title, and the add button shrinks to its glyph

### Requirement: A list groups its rows into cards
Every list screen SHALL group its rows into rounded cards under a section header: mail by the day a message arrived, contacts by their letter with conflicts first under a header of their own, the agenda by the day an entry starts. Pressing a contacts letter header SHALL select that section, or clear it when it is all selected.

#### Scenario: Two days of mail
- GIVEN messages from today and from yesterday
- WHEN the mail list is shown
- THEN today's sit in one card under Today and yesterday's in another under Yesterday

### Requirement: A list bar carries its actions inline
Every list screen SHALL carry its actions as bar buttons with no overflow: on contacts the birthdays, the duplicate remover and one import/export button opening a menu of the two, then on every list the filter last, accented while it hides anything. A list's search SHALL sit in its header, under the large title, not in the bar.

#### Scenario: The contacts bar
- WHEN the contacts list is shown
- THEN its bar carries birthdays, duplicates, import/export and the filter after the burger, and its search field sits under the large title

### Requirement: A deleted account takes its mail and calendars
Deleting an account SHALL drop its mailboxes and their messages, whatever its outbox still holds, and its calendars and their events. Its contacts SHALL move into the on-device book.

#### Scenario: Deleting a mail account
- GIVEN an account with synced mail
- WHEN it is deleted
- THEN none of its messages is listed

### Requirement: A placement's status is derived from the row
The store SHALL derive what a placement owes rather than store it, by the first rule that applies (pimdir SYNC §3): conflict when either the binding or the item is conflicted, tombstone when the item is deleted and the source binds it, created when the source binds it with no base or does not bind it at all, dirty when the flags differ from the base's, both known, or a mutable kind's body differs from the base's, clean otherwise. An item no source binds and the store holds no body for SHALL be projected for nobody. A placement holding no body SHALL project below full, whatever the stored level claims.

#### Scenario: A staged edit
- GIVEN an item whose body moved past the one its base holds
- WHEN the collection is loaded
- THEN the placement comes back dirty, which is what the merge derives its push from

#### Scenario: A message the reader opened
- GIVEN a stored message, an immutable kind, whose body a read filed
- WHEN the collection is loaded
- THEN the placement comes back clean, the bytes owing no upload

#### Scenario: A marker set that only reordered
- GIVEN a stored flag set naming what the base names, in another order
- WHEN the collection is loaded
- THEN the placement comes back clean

#### Scenario: A body a remote change dropped
- GIVEN an item whose object a refresh released
- WHEN the collection is loaded
- THEN the placement projects at most meta, so an upgrade refetches it

### Requirement: A staged removal is a tombstone the load hands back
An item staged for removal SHALL be kept, marked, and SHALL be loaded back to the engine as a tombstone until a push has carried it. It SHALL leave every listing at once, and an edit after it SHALL revive it.

#### Scenario: The delete is derived
- GIVEN a bound item the reader deleted
- WHEN the collection is loaded
- THEN the placement comes back as a tombstone, which is what the merge derives its remove push from

#### Scenario: The row is not resurrected
- GIVEN a staged removal the sync has not carried yet
- WHEN the remote enumerate still lists the member
- THEN it is not read as one to add back

#### Scenario: An edit beats a delete
- GIVEN a staged removal
- WHEN the item is written again
- THEN the row is revived rather than left marked

### Requirement: An item's page groups its sections into cards
The contact editor and the entry page SHALL draw each section as one rounded card holding its label, its add action and its rows.

#### Scenario: A contact with two phones
- GIVEN a contact with two phone numbers
- WHEN its editor opens
- THEN the phones label and both numbers sit in one card

### Requirement: The contacts list selects like mail
A long press on a contact SHALL start a selection, its disc turning into a check, with no checkbox on the row.

#### Scenario: Selecting a contact
- GIVEN the contacts list
- WHEN a contact is long pressed
- THEN its disc shows a check and the bar shows the count

### Requirement: The three lists share one row
A contact row and an agenda row SHALL take the mail row's shape: a disc, then three lines, a hairline parting the rows of a card. A contact row SHALL lead with the name, then the phone or else the email, then its addressbook and account, naming a card an account holds before one on the device. An agenda row SHALL lead with the entry ended by when it starts, then the kind of entry by name and how long it runs, then its calendar and account.

#### Scenario: A contact on two cards
- GIVEN a contact held in the on-device book and in an account's addressbook
- WHEN the contacts list is shown
- THEN its row names that addressbook and that account

### Requirement: An empty list says so below its header
A list with nothing to show SHALL centre its empty state in the space its header leaves below it, clear of the search, the chips and the week card.

#### Scenario: An empty day
- GIVEN a day with no entry picked in the week card
- WHEN the agenda renders
- THEN the empty state sits under the week card, not behind it

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
The store SHALL keep, per collection and source, the round under way (its scope, whether it lists only the band its coverage lacks, its resume cursor, the checkpoint a page handed) and the coverage the last closed round left, read and written through io-pimdir's canonical statements; it SHALL stamp every binding a page lists with the round's id, and hand the engine, while a round is open, the bindings no page stamped whose date is in its scope, or unknown on a round over its whole scope. A band round lists by a date filter that never returns an undated member, so its absence there SHALL NOT be read as a deletion. A store written before rounds recorded their kind SHALL gain the column on open, its open round reading as one over its whole scope.

#### Scenario: A round cut off
- GIVEN a round whose first page landed
- WHEN the collection is loaded
- THEN the round, its cursor, its scope and its kind come back with it

#### Scenario: An undated message across a widening
- GIVEN a message with no date the store binds
- WHEN a widening lists the band below the coverage
- THEN the message stays, and only a round over the whole scope or a delta can find it gone

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

### Requirement: A local action is visible before any sync
Every action a user takes on an item (create, edit, flag, delete, send) SHALL be staged as a pimdir mutation, never as a direct row write, and SHALL show in every list it concerns at once, a relocation's target included. The bridge SHALL carry pimdir's `Move` and `Copy` beside `Add`, `Edit`, `SetFlags` and `Remove`, and the sync SHALL carry each staged change out as staged (pimdir SYNC section 4): a `Remove` naming a destination as a server move, one naming none as a server delete, an `Add` with an origin as a server-side copy and one without as an append, never one turned into another. A connector that cannot carry one out, a relocation into another account's collection or any calendar relocation, SHALL reject it. A staged removal SHALL be listed nowhere. A row SHALL carry no mark of whether its change was pushed yet.

#### Scenario: A message deleted offline
- GIVEN a message in the inbox of an account with a trash
- WHEN it is deleted, offline
- THEN it shows in the trash at once, and no longer in the inbox
- AND the next sync moves it on the server

#### Scenario: A contact saved offline
- GIVEN no network
- WHEN a contact is created in an account's address book
- THEN it is listed at once, staged as an `Add`

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

### Requirement: An account that is off takes no part
An account's settings SHALL switch the account on and off. An account that is off SHALL NOT be synced by any sync (the drawer's, a list's pull, a first sync, the mail fill), SHALL NOT be listed by a filter, and its items SHALL NOT be listed. Its data SHALL stay stored, and switching it back on SHALL bring both back. The phone's mirror of its address books is local and SHALL be left as it is.

#### Scenario: A work account on the weekend
- GIVEN an account switched off in its settings
- WHEN the drawer's sync runs
- THEN the account is not contacted, its card says Deactivated, and no list or filter shows it

### Requirement: A role gives direct access across accounts
The mail list SHALL offer role chips, Inbox, Sent, Drafts, Trash, Junk and Archive, at most one on: a chip SHALL narrow the list to the mailboxes holding that role (pimdir's collection role, as the source states it) of the accounts the filter shows, ticked or not, a chip being a view of its own, and no chip on SHALL narrow nothing. The outbox holds no role. Gmail, listed as one account, SHALL hold the roles of its system labels (INBOX, SENT, DRAFT, TRASH and SPAM as inbox, sent, drafts, trash and junk); Gmail has no archive label, so Archive SHALL show none of its mail. Contacts and calendars SHALL offer a Default chip narrowing to the default collection of every shown account. A chip SHALL NOT sync by itself; a pull while it is on syncs what it shows.

#### Scenario: Every trash
- GIVEN three shown mail accounts, their trashes unticked
- WHEN the Trash chip is chosen
- THEN the list shows the three trashes merged, and nothing syncs

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

### Requirement: Deleted items can be restored or purged
A drawer row SHALL open Deleted items: every item the store holds deleted, across mail, contacts and calendars, each with its summary, domain, account, last collection and deletion time, the items a source still binds first, then newest deletion first, shown 100 at a time. The app runs io-pimdir without `client`, so the list SHALL be read through pimdir's canonical `list_retained` statement, one collection a keyset page at a time, and ordered in memory. An item a source still binds SHALL say it is waiting for the server and offer no action. An item held as summary only SHALL say it is not stored on the device. An item whose body the store holds SHALL be restorable into a writable collection of its kind and account the user picks, its last one preselected (else the account's default), as a local creation of the stored body, byte for byte, the next sync uploads: back into its last collection it revives the retained row and its public id (STORAGE §11.1), elsewhere it is a new placement, a contact or event keeping its UID, a mail named from its body. A restore over an item the target holds live SHALL be refused. Mail SHALL be restorable on IMAP only, where the push appends a create with no origin, the restored message taking a new UID once uploaded, its link id being the handle; Graph, Gmail and JMAP SHALL refuse it, saying it is not supported for this account yet. The page SHALL show the space a purge releases and, on confirmation, purge every item retained until then and collect the bodies nothing references any more (`purge_retained_before`, `collect_garbage`), leaving the waiting items. No orphan blob walk SHALL run, since the app's writers take no io-pimdir staging lock. The Free space button SHALL carry the size the purge reclaims, as the summary line does.

#### Scenario: A contact deleted by mistake
- GIVEN a contact deleted and synced
- WHEN it is restored from Deleted items into its address book
- THEN it shows in the address book at once, and the next sync uploads it

#### Scenario: A mail never opened
- GIVEN a deleted mail whose body was never fetched
- WHEN Deleted items lists it
- THEN it shows, says it is not stored on the device, and offers no restore

#### Scenario: A mail in Latin-1
- GIVEN a deleted IMAP message opened once, its body in an 8-bit charset
- WHEN it is restored into its mailbox
- THEN the stored body is the bytes the server sent, and the next sync appends them

#### Scenario: Free space
- GIVEN retained items and one waiting for the server
- WHEN Free space is confirmed
- THEN the retained items and their bodies go for good, and the waiting one stays

### Requirement: A sync that went through says nothing
A sync that went through SHALL NOT pop up a report: no count of what came in or went out, no count of messages sent from the outbox, no count of conflicts left, the lists showing what the pass brought and a conflicted row carrying its own mark. A pull turned down because a background run holds the sync SHALL say nothing either, that run's dock already showing. A failure SHALL still open its error dialog.

#### Scenario: A quiet pass
- GIVEN a contacts pass bringing three cards and leaving one conflict
- WHEN it ends
- THEN no toast shows, and the conflicted contact carries its mark in the list

### Requirement: The store runs on the bundled SQLite
The app SHALL open every database it owns, the pimdir store included, on the SQLite it bundles, version 3.37 or newer with JSON1, on every Android it supports (API 26 and up), and SHALL NOT open one on the platform's `android.database.sqlite`, whose version follows the device.

#### Scenario: An Android 8 device
- GIVEN a device whose platform SQLite is 3.18
- WHEN the app creates the store
- THEN the `STRICT` tables are created and the `RETURNING` and `json_each` statements run, on the bundled SQLite

### Requirement: The process holds one store
Every caller in the process, the activity, the sync adapters, the background job, the phone queue and the time-zone receiver, SHALL share one store (`PimdirDb`) and one card store, so no call leaves a connection pool open behind it.

#### Scenario: A phone sync
- GIVEN an address book mirrored on the phone
- WHEN the system runs its sync adapter
- THEN the adapter uses the process's store, and no SQLite connection is reported leaked

### Requirement: A write records the revision the server holds
A pushed create or edit SHALL record the revision the server holds the item at: the ETag the write answered, read as the string it is (quotes of its own included), or, when the write answered none, the one a read of that member answers. A read that fails SHALL leave the revision unknown and SHALL NOT fail the write.

#### Scenario: A server answering no ETag
- GIVEN a CalDAV server that answers a PUT with no ETag, as Google's does
- WHEN an entry created on the phone is pushed, then deleted on the phone
- THEN the pass after the delete pushes it, rather than reading the listing as a change made elsewhere and pulling the entry back

#### Scenario: An ETag carrying quotes
- GIVEN a Google Calendar event edited on the phone, whose new ETag is `"3583433504310878"`
- WHEN the edit is pushed
- THEN the binding's revision is `"3583433504310878"`, the one the next listing names, and a delete staged after it is pushed

### Requirement: A bound member keeps its identity
A contacts or calendar driver SHALL name a member its source already binds by the identity the store files it under, whatever identity its body states now, so the store never retires an item for its body alone.

#### Scenario: Google rewrites a card's UID
- GIVEN a card created on the phone, which Google stored with its UID rewritten to its own id
- WHEN the card is edited elsewhere while the phone stages an edit of its own
- THEN the two edits merge onto the same card and the merge is pushed, rather than the card being replaced and the phone's edit dropped

### Requirement: A scoped load reads what it names
A load scoped to some handles or link ids SHALL read those rows alone, whatever the length of the list: the list SHALL be bound as one value (a JSON array read through `json_each`), never widened back to the whole collection, and a handle scope SHALL seek the source's bindings by handle rather than walk the collection. An unbound row SHALL still be reached under the provisional handle of its link id.

#### Scenario: A listing page of a thousand
- GIVEN a mailbox of 275k messages
- WHEN a round's page of 1000 members is loaded by handle
- THEN 1000 placements come back, read by key, and the page costs what it holds rather than the mailbox

### Requirement: A store write is never read through a cursor
A write SHALL run as a statement and count what it changed (`changes()`), its `RETURNING` clause dropped, never stepped through a cursor: a cursor runs its statement again each time it refills its window, so a delete read through one runs again past its first window. A purge whose pins a statement would return SHALL leave them to the collector's recount.

#### Scenario: Narrowing a large account
- GIVEN 120k messages below a new bound
- WHEN the bound is narrowed
- THEN all 120k are collected once and counted, in one pass

### Requirement: A deep read seeks where it lands
A keyset page, the frontier's dates and the body plan SHALL state the bound their cursor implies on the sort key, so the index serves them from the cursor down rather than from the newest row. A count with no attachment chip SHALL NOT read the summary. A page reached with no page above it read (a fling) SHALL read from the start of the day its first row falls on, by the per-day counts the list already holds, paying for that day's rows ahead of it and no more.

#### Scenario: A fling to the end of a million messages
- GIVEN a merged list of 1M stored messages
- WHEN it is flung to its last page
- THEN that page reads from its own day, in milliseconds

### Requirement: A body filed ahead of its write keeps its file
A body moved into the blob store before the write naming it (a downloaded message) SHALL be pinned from before it is moved in until that write has returned, and a collector SHALL NOT unlink a pinned body, leaving it for a later run. A collector SHALL read an object gone and unlink its file inside a transaction of its own, so no writer is between filing a body and committing its row. A write recording a body already filed SHALL be refused when the file is missing, the batch rolled back and the body still owed.

#### Scenario: A download racing the last reference to the same bytes
- GIVEN a message downloading a body whose bytes another message holds
- WHEN the other message drops them, its collector running before the download's write lands
- THEN the file stays, and the downloaded message holds its body once the write lands

#### Scenario: A body whose file is missing
- GIVEN a write recording a downloaded body whose file is not in the blob store
- WHEN it is applied
- THEN it is refused, and the message stays at meta, its body owed
