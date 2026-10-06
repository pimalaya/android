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

### Requirement: An unnamed handle is a probe
A placement carrying no link id SHALL be recorded as a probe of its source, never as an item keyed by its handle, and SHALL be loaded back as a probed placement. A named placement SHALL forget the probe its handle held, and a handle bound to another link id SHALL have that binding retired first: a handle names one item per source.

#### Scenario: An enumeration reports a member
- GIVEN a collection whose enumeration yields handles and no identities
- WHEN the store writes them
- THEN each is a probe, no item is keyed by a handle, and the next load offers them as probed placements

#### Scenario: A fetch names a probed handle
- GIVEN a probed handle
- WHEN the upgrade resolves its identity and upserts the placement
- THEN the probe is forgotten and the handle is bound exactly once

#### Scenario: A resource replaced in place
- GIVEN a handle bound to one link id
- WHEN an upsert of the same handle carries another
- THEN the binding it held is retired and the handle binds the new identity alone

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

### Requirement: The filter offers what it can hide
The filter's account axis SHALL list the accounts covering the domain on screen, and SHALL NOT list the on-device account, whose one address book is on the collection axis.

#### Scenario: A contacts-only account on the mail list
- GIVEN an account connected for contacts alone
- WHEN the filter is opened over the mail list
- THEN the account is not listed

### Requirement: The drawer switches domains
The drawer SHALL open on a title bar, a closing cross beside the app's name, then one row per domain, an icon and its name like the footer's actions, mail, contacts then calendars, the one on screen drawn on an accent pill, over the account rows and the footer of actions. A list screen's bar SHALL carry the burger and the domain's name, and SHALL NOT carry the domain buttons.

#### Scenario: Switching to the calendars
- GIVEN the mail list
- WHEN the drawer is opened and the calendars row is pressed
- THEN the drawer closes on the agenda, swapped in with no slide, its bar titled with the calendars' name

### Requirement: A list bar carries its actions inline
Every list screen SHALL show the same add glyph, and SHALL carry its actions as bar buttons with no overflow: on contacts search, the birthdays, the duplicate remover and one import/export button opening a menu of the two, then on every list the filter last, accented while it hides anything. An open search SHALL take the bar up to its clear cross, beside the filter.

#### Scenario: The contacts bar
- WHEN the contacts list is shown
- THEN its bar carries search, birthdays, duplicates, import/export and the filter after the burger and the title

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
