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
A CardDAV or CalDAV listing, delta or discovery answer that is not one whole `multistatus` document (a page of another kind, a document cut short, an empty body) SHALL fail its request, as io-webdav 0.8 fails it, and SHALL NOT be read as a shorter listing, whose missing members would read as deleted.

#### Scenario: A listing cut short
- GIVEN a CardDAV server whose listing of two cards ends before its closing tag
- WHEN the book is listed
- THEN the listing fails, and no card is read as gone

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
A Google contact update refused for a stale or missing etag SHALL re-read the person and send only the fields the phone changed and the server did not, under the fresh etag; a field both sides changed differently SHALL NOT be written, the update being refused as a conflict. An update refused with no change listed after it SHALL become a conflict of its binding, whose body the next hydrate reads, merged three ways or left for the conflict form. Only CardDAV SHALL retry an update unguarded. JMAP and Microsoft Graph updates are not guarded yet (change guarded-contact-updates).

### Requirement: An edit reaches every copy of the contact
The editor's save of a card held in several books of one account SHALL be staged on every book copy whose body equals the edited copy's, and SHALL leave alone a copy holding an unsent change of its own.

### Requirement: Unticking a book never drops an unsent change silently
Unticking the book that holds the only copy of a change the server has not got SHALL ask whether to keep it. Keeping a change of a card already sent SHALL merge it three ways against that card's stored base onto what the staying books hold, staged as an edit when clean and opening the conflict form on a collision, nothing written; keeping a change of a card never sent SHALL open the conflict form. Dropping it SHALL leave it in Deleted items. A deletion of that copy made in the phone's own Contacts app SHALL wait on the phone until the change is sent, the copy kept in the app and the wait reported in "Not sent yet".

#### Scenario: Keeping an edit over a remote edit
- GIVEN a contact whose label copy holds an unsent rename while Google changed its phone number
- WHEN the user unticks the label and keeps the change
- THEN the server ends with the rename and Google's phone number
