---
cairn: spec
capability: carddav-sync
status: current
---

# CardDAV sync

An addressbook is reconciled by enumerating its members incrementally: the RFC 6578 `sync-collection` REPORT with the cursor the previous round returned, or a complete round with no cursor. A round reports whether it was complete, which is what tells the engine a member the listing does not carry was removed rather than merely unchanged.

The other backends answer the same shape through their own mechanisms: a Graph contacts delta round, a JMAP `ContactCard/changes` round, a People connections sync.

### Requirement: A CardDAV collection is enumerated whatever the server implements
A collection sync SHALL run the RFC 6578 `sync-collection` REPORT, and SHALL enumerate with a `PROPFIND` at Depth 1 when the server answers that it implements no such report. A round carrying a cursor that meets the same refusal SHALL be re-run as an initial round rather than answered from the fallback's tokenless snapshot.

#### Scenario: A server with no sync-collection
- GIVEN a CardDAV server refusing the report
- WHEN an initial sync runs
- THEN the collection is enumerated with `PROPFIND` and the round reports itself complete

### Requirement: A DAV listing that is not whole is refused
A CardDAV or CalDAV listing, delta or discovery answer that is not one whole `multistatus` document (a page of another kind, a document cut short, an empty body) SHALL fail its request, as io-webdav 0.8 fails it, and SHALL NOT be read as a shorter listing, whose missing members would read as deleted. A card listing naming a card without its data (a 403 or 404 row, io-webdav's `unreadable`) SHALL fail too; a multiget row answered without its data SHALL be logged and, unless it is a 404 or a 410 (the card gone, left out), read alone by a `GET`; one still unreadable alone (a 403, a 5xx) SHALL fail the fetch, and with it the round, rather than be left out, which a complete round would read as removed (neverest's rule). A calendar multiget SHALL read its rows the same way.

#### Scenario: A listing cut short
- GIVEN a CardDAV server whose listing of two cards ends before its closing tag
- WHEN the book is listed
- THEN the listing fails, and no card is read as gone

#### Scenario: A multiget row refused
- GIVEN a complete CardDAV round whose multiget answers one member 403
- WHEN the card is read alone and answers, or answers 403 again
- THEN the card is stored, or the round fails; it is never retired

### Requirement: A Graph book reads its changed contacts 20 to a batch
A Graph addressbook pass SHALL take a contact's body from the complete round's listing when it carries one, and SHALL read every other body it needs 20 requests to a `$batch`, each request the one a single contact read sends, a request the batch could not serve being sent again on its own. A contact Graph no longer holds when it is read SHALL be left out, and the rest of the book stored. Each read SHALL log how many contacts it was asked for, how many the listing gave, how many it read in how many batches and its network time.

#### Scenario: Hundreds of contacts edited in Outlook
- GIVEN a Graph book of which 120 contacts were edited elsewhere since the last pass
- WHEN the book syncs
- THEN the delta names them and their bodies are read in seven `$batch` calls (the pass asks 64 at a time: four and three), rather than 120 requests

#### Scenario: A contact deleted on Outlook since the delta
- GIVEN a contact the delta names and Outlook deletes before it is read
- WHEN the pass reads it
- THEN it is left out and the rest of the book is stored

### Requirement: Removing a phone account never drops a phone edit
An Android account SHALL be removed only after its phone pass brought every phone edit into the store. Deleting an app account SHALL run its books' phone pass first and SHALL stop, removing nothing, when one fails. A book's phone switch turned off SHALL keep its account when the last phone pass fails.

#### Scenario: Deleting an account with an unsynced Contacts-app edit
- GIVEN a contact added in the Contacts app under a book's account, not yet in the store
- WHEN the account is deleted in the app
- THEN the contact is brought into the store first and moves to the on-device book with the others

### Requirement: A book wiped on the phone waits for the user
A phone pass finding more than half of at least 20 of a book's members deleted on the phone SHALL push none of those deletions and SHALL leave their rows deleted on the phone. When the app's sync ends, it SHALL ask whether to delete them on the server, put them back on the phone, or decide later. A deleted row SHALL be erased only after the store recorded its deletion.

#### Scenario: Another app wipes the contacts
- GIVEN a book of 500 contacts mirrored on the phone
- WHEN another app deletes all 500 raw contacts
- THEN the next pass deletes nothing on the server, and the app asks what to do with the 500

#### Scenario: A few deletions
- GIVEN a book of 500 contacts mirrored on the phone
- WHEN the user deletes 3 of them in the Contacts app
- THEN the next pass deletes the 3 on the server without asking

### Requirement: A card is pushed by its server handle
Every push of a contact (update, delete, membership change), on every backend, SHALL name the card by the handle its server gave it, never by the store's link id, which on a card created on the phone is the app's UID. A membership add SHALL name the card by the handle of a book that already holds it.

#### Scenario: A label added to a contact created on the phone
- GIVEN a Google contact created in the app and synced
- WHEN it is added to another label
- THEN the request names the person Google created (`people/c...`), and Google takes it

### Requirement: A card moves between books by its memberships
On an account whose cards live once and belong to several books (Google's labels, JMAP address books), adding a card to a book SHALL be a membership patch of the card the account already holds under the same body (pimdir's origin_for_link, scoped to the account and the contact kind, a tombstoned holder included), and taking it out of a book SHALL be a membership removal while another book still holds it; a delete SHALL only be sent when the card leaves its last book. A move SHALL hold the source's removal until the target's add lands (pimdir STORAGE §15.3 supersession), and an accepted update or patch SHALL take the card out of the books it is leaving, so their tombstones settle rather than revive. A book that took a card SHALL be listed again in the same pass, the patch answering no revision. A card the account's listing moved out of a book SHALL stay listed there while another book stages its add, so the add is a membership patch, never a second card. A staged removal whose card is listed with a body already agreed on the account (the superseding add's included) SHALL be read at its base revision, not revived; an edit made elsewhere still revives it.

#### Scenario: A tick while the web moved the contact
- GIVEN a Google contact the web moved from Contacts to a label the phone does not sync
- WHEN the phone ticks Seed amis on its old copy, then syncs
- THEN one membership patch adds Seed amis, and no second person is created

#### Scenario: A move landing half
- GIVEN a move whose edit reached Google but whose membership patch failed
- WHEN the next pass syncs the source book first
- THEN the source's removal is not revived, and the move completes

#### Scenario: A move to another label
- GIVEN a Google contact in Contacts
- WHEN the user moves it to Seed amis
- THEN one membership patch adds Seed amis and removes Contacts, the person keeps its id, and the next pass sends nothing

#### Scenario: A label unticked
- GIVEN a Google contact in Contacts and Seed amis
- WHEN the user unticks Seed amis
- THEN Seed amis is removed from its memberships and the person stays in Contacts

### Requirement: An add the server cannot take whole is held and reported
An add whose body differs from every copy the account holds SHALL NOT be sent as a bare membership patch, nor as a second card, nor over the server's copy. On Google, an add superseding a removal (a move carrying an edit) SHALL be delivered as an update of the card the server holds, guarded by that card's base, then the membership patch; a conflict keeps it held. Any other such add SHALL be held, staged, and reported. At the end of a sync, and again whenever the set changes, a "Not sent yet" dialog SHALL list each held add, each held move removal and each deletion made in the phone's own Contacts app that waits for a held change, by contact and book, and the contact's row SHALL carry the diverged marker, reopening that report. An add whose body another book stages as its own unsent edit SHALL NOT be reported. The report SHALL be computed from the store (and the phone's deleted raw contacts) when shown, so it survives a restart and drops what was sent.

#### Scenario: A contact edited and moved in one save
- GIVEN a Google contact in Contacts
- WHEN the user renames it and moves it to Seed amis in one save, then syncs
- THEN Google's person is updated under its etag, then moved, and nothing is reported

#### Scenario: A move whose edit meets a remote edit
- GIVEN a Google contact renamed and moved on the phone while the web edited the same name
- WHEN the phone syncs
- THEN nothing overwrites Google's copy, the edit stays on the phone, and "Not sent yet" names it

### Requirement: A contact update never writes over a remote change
A Google contact update refused for a stale or missing etag SHALL re-read the person and send only the fields the phone changed and the server did not, under the fresh etag; a field both sides changed differently SHALL NOT be written, the update being refused as a conflict. An update refused with no change listed after it SHALL become a conflict of its binding, whose body the next hydrate reads, merged three ways or left for the conflict form. Every Google update SHALL be the vCard's projection merged over the person read right before it (io-gpeople's `GpeoplePerson::merge_unprojected`), the etag guarding the race between them: People replaces a masked field whole, so what the vCard cannot hold (a second organization and an organization's location, an HTML biography, a foreign `clientData` entry, sub-fields) SHALL be sent back as held. An update with no base SHALL mask only the fields its vCard shows apart from the person read, never every field. Only CardDAV SHALL retry an update unguarded. JMAP and Microsoft Graph updates are not guarded yet (change guarded-contact-updates).

#### Scenario: A Google contact with two organizations
- GIVEN a Google contact with two organizations, the first with a location, and an HTML biography, which the vCard shows as one organization without its location and no biography
- WHEN the phone changes its phone number and the first organization's title
- THEN People holds both organizations, the first with its location and the new title, the biography, and the new number

### Requirement: An edit reaches every copy of the contact
The editor's save of a card held in several books of one account SHALL be staged on every book copy whose body equals the edited copy's, and SHALL leave alone a copy holding an unsent change of its own.

### Requirement: Unticking a book never drops an unsent change silently
Unticking the book that holds the only copy of a change the server has not got SHALL ask whether to keep it. Keeping a change of a card already sent SHALL merge it three ways against that card's stored base onto what the staying books hold, staged as an edit when clean and opening the conflict form on a collision, nothing written; keeping a change of a card never sent SHALL open the conflict form. Dropping it SHALL leave it in Deleted items. A deletion of that copy made in the phone's own Contacts app SHALL wait on the phone until the change is sent, the copy kept in the app and the wait reported in "Not sent yet".

#### Scenario: Keeping an edit over a remote edit
- GIVEN a contact whose label copy holds an unsent rename while Google changed its phone number
- WHEN the user unticks the label and keeps the change
- THEN the server ends with the rename and Google's phone number

### Requirement: A card is keyed by its UID as STORAGE §9 assigns
A card listed or fetched from any backend, or read off the phone, SHALL be filed under the key pimdir STORAGE §9 assigns from its `UID` (offline-store "An item's link id follows STORAGE §9"): its `UID`, `esc:` before one beginning with a reserved prefix, the `hash:` fallback over its bytes for a blank or missing one; a card created in the app SHALL be staged under the same key, derived from the body it saves. A create SHALL be filed on a CardDAV server as its stated `UID` and `.vcf`, and one keyed by a derived key under a fresh name.

#### Scenario: A blank UID
- GIVEN a CardDAV card stating `UID:` followed by spaces
- WHEN the book is listed
- THEN the card is filed under `hash:` and sixteen hexadecimal digits, never under its resource name

### Requirement: A card that is not UTF-8 is kept as served
A card whose bytes are not UTF-8 (a Latin-1 vCard 2.1) SHALL be stored as its server sent them (offline-store "A DAV body is stored as served"), shown on the phone read lossily, and never written back as text: an edit of it in the app is refused, one in a contacts app is put back from the store, and the push of such a body is answered refused. A copy of it made in the app (the editor ticking it into another book) SHALL be refused as its edit is, nothing staged in the other book.

#### Scenario: A Latin-1 card ticked into another book
- GIVEN a CardDAV card stored as Latin-1 bytes
- WHEN the editor ticks it into another book of the account and saves
- THEN the save is refused and the other book holds nothing of it

### Requirement: A contact push refused as it stands is held
A contact push the server refuses as it stands SHALL be answered refused with the server's reason, held by the engine on its binding and reported every sync, as a mail push is (offline-store, "A change refused as it stands is held"), and the book's other changes SHALL be pushed. The bridge SHALL read the refusal off the answer: a CardDAV status naming the request (400 to 499 but 401, 407, 408, 409, 412, 423, 425 and 429) whose body names no exceeded quota, a People answer of such a status naming no rate limit nor quota, a Graph one naming neither `ErrorQuotaExceeded` nor a rate, inside a `$batch` as alone, and a JMAP `ContactCard/set` error naming the change (`forbidden`, `invalidProperties`, `invalidPatch`, `blobNotFound`, `tooLarge`, `singleton`), never `notFound`, `overQuota` nor `rateLimit`. A card gone (404) SHALL stay gone first, a removal converging on it, and a 412 SHALL stay the stale guard, retried unguarded when the listing proves the card unchanged and else rejected. A People batch create or delete refused whole still fails the round: no one card can be told from the others.

#### Scenario: One card of three refused
- GIVEN three cards edited on the phone in a CardDAV book, the server answering one update 415
- WHEN the book syncs
- THEN the other two land, the refused one is recorded on its binding and reported, and the next sync asks the server nothing for it

### Requirement: A DAV listing is bounded
A `sync-collection` drain SHALL follow the token of each truncated page (a `507` row, RFC 6578 section 3.6), 32 rounds at most, and SHALL stop on a truncated page naming no new token or on its last round. The listing so cut SHALL answer incomplete, its token the last one known, so the engine retires no member it did not list; the next sync lists again from that token.

#### Scenario: A server cutting every answer short
- GIVEN a CardDAV server answering every `sync-collection` truncated with no token
- WHEN an initial sync runs
- THEN one REPORT is sent, the round answers incomplete, and no card is retired
