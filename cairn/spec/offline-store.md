---
cairn: spec
capability: offline-store
status: current
---

# Offline store

The app keeps one pimdir store for every account and every domain: `collections.kind` carries the media type (`message/rfc822`, `text/vcard`, `text/calendar`) and `collections.account` groups by account, so the merged view's two filter axes are columns rather than three databases. Bodies live in a content-addressed blob directory beside the database, referenced by hash and refcounted.

The schema is io-pimdir's, handed to Java over JNI rather than transcribed, and executed against Android's own SQLite. The crate is taken without its `client` feature: the platform ships SQLite, and compiling a second engine into every ABI would work against the app's first design goal.

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
- THEN the column is dropped, or kept with a log where the platform's SQLite cannot drop one

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
The store SHALL record what a reader lists an item from in its kind's summary table and the people it names in `item_address`, as pimdir STORAGE Annex A defines them, written through the canonical statements. It SHALL NOT keep a summary convention of its own in the item row, and SHALL derive one through io-pimdir wherever the body is on hand.

#### Scenario: A card is written
- GIVEN a vCard with a `FN`, a `UID` and two `EMAIL` properties
- WHEN the app writes it
- THEN `contact_summary` holds its name, uid, kind and org, and `item_address` holds both emails under the `email` role in document order

#### Scenario: A message listed but not fetched
- GIVEN an IMAP envelope and no body
- WHEN the mail mirror writes the row
- THEN `mail_summary` holds the same fields the body's derivation would, built from the envelope

#### Scenario: A summary the writer did not restate
- GIVEN an item whose summary is stored
- WHEN a write carries none
- THEN the stored summary is kept, on the same terms as the sort key

### Requirement: A placement no source binds is offered under its provisional handle
The store SHALL hand out `U+0001` followed by the link id as the handle of a placement the source does not bind (SYNC §2), and SHALL resolve that handle back to the identity it spells. A push SHALL name the resource by that identity, never by the provisional handle.

#### Scenario: A card staged for a book the phone has never seen
- GIVEN an item with no binding for the phone spoke
- WHEN that spoke loads the collection
- THEN the placement carries the provisional handle, and a row read under it resolves to the item

#### Scenario: The create is pushed
- GIVEN a staged create under its provisional handle
- WHEN the push offers it to the server
- THEN the resource is named after the card, and the binding takes whatever handle the push assigns

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

### Requirement: A rebuilt handle is not a removal
The store SHALL read a `rekeyed` drop as this row going and the item staying, on the same terms as a `superseded` one.

#### Scenario: A rebuilt spine
- GIVEN a collection whose handle space the remote renumbered
- WHEN the engine drops every old handle as `rekeyed` and upserts the new ones
- THEN no item is retired and no delete propagates to another source

### Requirement: A refresh keeps the bodies it does not restate
A write carrying no body SHALL keep the object the item already holds, and an item's level SHALL follow what it holds: meta with no body, full with one.

#### Scenario: A sync after a read
- GIVEN a message whose body was stored by opening it
- WHEN the mailbox is refreshed
- THEN the stored body survives the refresh

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

Submitting a message SHALL NOT be retried: a submission that was accepted and then failed to file its copy is indistinguishable from one that never went, and running it again would send the message twice. Its failure SHALL leave the message in the outbox.

#### Scenario: An idle timeout
- GIVEN a session the server has since closed
- WHEN the next marker is written on it
- THEN the session is reopened and the write succeeds

#### Scenario: The reopen fails too
- GIVEN no network at all
- WHEN a verb runs on a held session
- THEN the failure is reported rather than retried forever

#### Scenario: A submission that failed
- GIVEN a server that accepted a message and then refused the filed copy
- WHEN the drain reports it
- THEN nothing is sent a second time

### Requirement: A sync says what it is working on, in every domain
The modal sync dialog SHALL name what the pass is on and what it is doing: the domain being reconciled as its title (*Emails*, *Contacts*, *Calendars*), and the step it stands at as its detail line. The three domains SHALL report both, so a wait reads the same whichever one is being synced.

Neither line SHALL ever be empty while the dialog is up. It SHALL open naming the first domain the pass reaches over a line saying it is preparing, and SHALL replace the title as the pass moves to the next domain and the detail line as it steps.

#### Scenario: A pass over every domain
- GIVEN the drawer's sync
- WHEN it moves from contacts to mail to calendars
- THEN the title reads *Contacts*, then *Emails*, then *Calendars*

#### Scenario: The roster round
- GIVEN a pass that has to list an account's mailboxes or calendars first
- WHEN it starts
- THEN the detail line is set before that round, never a title over a blank line

#### Scenario: The first frame
- GIVEN a sync the user has just asked for
- WHEN the dialog opens, before any round trip
- THEN it names the domain being synced over a line saying it is preparing, rather than one line over an empty one

### Requirement: The drawer's sync covers every domain
The drawer's sync SHALL reconcile contacts, then mail, then calendars, and SHALL report one failure at most, the contacts one first.

#### Scenario: A marker staged on the phone
- GIVEN a message flagged in the reader
- WHEN the drawer's sync runs
- THEN the flag is pushed to the server

### Requirement: A list's pull syncs what it shows
Pulling a list down SHALL sync that list's domain alone, and within it only the accounts and collections the filter shows; the contacts pull SHALL also run the phone's pass. The drawer's sync SHALL take every domain, every account and every collection, whatever the filter hides.

#### Scenario: Pulling the agenda
- GIVEN two calendar accounts, one hidden by the filter
- WHEN the agenda is pulled down
- THEN only the shown account's calendars are synced, and no mail or contact is

### Requirement: The filter offers what it can hide
The filter's account axis SHALL list the accounts covering the domain on screen, and SHALL NOT list the on-device account, whose one address book is on the collection axis.

#### Scenario: A contacts-only account on the mail list
- GIVEN an account connected for contacts alone
- WHEN the filter is opened over the mail list
- THEN the account is not listed

### Requirement: The bottom bar switches domains
A bottom navigation bar SHALL switch between mail, contacts and calendars, in that order, the one on screen on a neutral indicator, mail carrying the count of unread messages among those listed. It SHALL show on the three lists only. The drawer SHALL open on the app's name beside a closing cross, then one card per account naming its address and the domains it covers, with a pill saying Deactivated when the account does not take part and otherwise when it last synced, then, fixed at the bottom, a line and the actions. Pressing a card SHALL open that account's settings. The drawer SHALL NOT list mailboxes. A list screen's bar SHALL carry the burger and SHALL NOT carry the domain buttons.

#### Scenario: Switching to the calendars
- GIVEN the mail list
- WHEN the calendars item of the bottom bar is pressed
- THEN the agenda is swapped in with no slide, the calendars item on the indicator

#### Scenario: An account the filter hides
- GIVEN an account hidden by the filter
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
The store SHALL keep, per collection and source, the round under way (its scope, its resume cursor, the checkpoint a page handed) and the coverage the last closed round left, read and written through io-pimdir's canonical statements; it SHALL stamp every binding a page lists with the round's id, and hand the engine, while a round is open, the bindings no page stamped whose date is in its scope or unknown.

#### Scenario: A round cut off
- GIVEN a round whose first page landed
- WHEN the collection is loaded
- THEN the round, its cursor and its scope come back with it
