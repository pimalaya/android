---
cairn: delta
change: guarded-contact-updates
---

# Delta

Folds into `spec/carddav-sync.md`: the added requirement beside "A contact update never writes over a remote change", which it modifies; the calendar requirement modified in `spec/offline-store.md`.

## ADDED Requirements

### Requirement: A contact save names what it read
The contact editor's save of an existing card (each book copy of an edit, the survivor of a merge of duplicates and each card it removes, each card a duplicate link gives a UID) SHALL reach the card by its current handle at write time and SHALL name the body it read (`if_match`), so a sync that changed the card while the editor or the review was open makes the save refused, never applied over it. A refused edit or link SHALL merge three ways (the body read, the body the save would write, the body the sync left): a clean merge is staged over the body the sync left, a collision opens the conflict form on the merge, nothing written until it is saved, and that save is made over the body the merge was made onto. A merge of duplicates SHALL stage as one transaction, refused whole once a sync replaced any card it read, the person told and merging again from the list as the sync left it. A link whose merge collides SHALL leave that card unlinked and say so.

#### Scenario: The server added a phone number while the editor was open
- GIVEN a contact open in the editor while a sync brings a phone number added on the server
- WHEN the editor saves a new name
- THEN the card holds the new name and the server's phone number, saved without asking

#### Scenario: Both sides renamed the contact
- GIVEN a contact open in the editor while a sync brings a rename made on the server
- WHEN the editor saves another name
- THEN nothing is written and the conflict form opens with both names; its save is staged over the server's version

#### Scenario: A duplicate changed on the server while the merge was open
- GIVEN two duplicates open in the merge form while a sync brings a change to the one the merge removes
- WHEN the merge is saved
- THEN nothing is staged, both cards stay as the sync left them, and the person is told

## MODIFIED Requirements

### Requirement: A contact update never writes over a remote change
A Google contact update refused for a stale or missing etag SHALL re-read the person and send only the fields the phone changed and the server did not, under the fresh etag; a field both sides changed differently SHALL NOT be written, the update being refused as a conflict. An update refused with no change listed after it SHALL become a conflict of its binding, whose body the next hydrate reads, merged three ways or left for the conflict form. Every Google update SHALL be the vCard's projection merged over the person read right before it (io-gpeople's `GpeoplePerson::merge_unprojected`), the etag guarding the race between them: People replaces a masked field whole, so what the vCard cannot hold (a second organization and an organization's location, an HTML biography, a foreign `clientData` entry, sub-fields) SHALL be sent back as held. An update with no base SHALL mask only the fields its vCard shows apart from the person read, never every field. Only CardDAV SHALL retry an update unguarded.

A JMAP update SHALL name the account state the pass's listing read, and the cards changed on the server since (`ContactCard/changes` from that state, every page) SHALL be read right before the `ContactCard/set` that carries the update: a card among them, or an update naming no state, or a state the server can no longer compute changes from, SHALL NOT be written but answered rejected, left pending for the next listing to list the server's change and merge it three ways or leave it to the conflict form. The set SHALL name the state those changes end at (`ifInState`), never the listing's: a card written between the two makes the server refuse the set whole (`stateMismatch`), and every change it carried SHALL be answered rejected, pending for the next pass's merge, never refused. A lone JMAP change SHALL be pushed as a round, so it is guarded the same way.

A Microsoft Graph update SHALL name the changeKey the card was last read at, and the contact SHALL be read right before its PATCH (Graph contacts take no If-Match): one held at another changeKey, or an update naming none, SHALL NOT be written but answered rejected as a stale If-Match is, left pending for the next listing's merge. The contacts of a round are read 20 to a `$batch`; a contact gone is left to its PATCH.

#### Scenario: A Google contact with two organizations
- GIVEN a Google contact with two organizations, the first with a location, and an HTML biography, which the vCard shows as one organization without its location and no biography
- WHEN the phone changes its phone number and the first organization's title
- THEN People holds both organizations, the first with its location and the new title, the biography, and the new number

#### Scenario: A JMAP card renamed on the server between the listing and the push
- GIVEN a JMAP card renamed on the phone, and renamed differently on the server after the pass listed the account
- WHEN the pass pushes the rename
- THEN the server keeps its name, and the next pass records the card as a conflict for the form

#### Scenario: A Graph contact given a phone number between the listing and the push
- GIVEN a Graph contact renamed on the phone, and given a phone number on the server after the pass listed the folder
- WHEN the pass pushes the rename
- THEN the PATCH is not sent, and the next pass merges both changes and sends them

### Requirement: A calendar or task save names what it read
A save of a calendar entry or a task (its page, a Clarify step, a list tick, a batch action, an occurrence delete, a split, a move to another calendar, a delete) SHALL reach the item by its current handle at write time and SHALL name the body it read (`if_match`, a remove or a move checking it in its own transaction), so a sync that bound or changed the item meanwhile makes the save refused, never applied over it. A refused save SHALL merge three ways (the body read, the body the save would write, the body the sync left): a clean merge is saved, a collision opens the entry page's conflict mode with both values, nothing stored until settled. A batch action SHALL skip and report an item whose merge collides; a task's completion alone is re-applied, last writer winning. Leaving a conflict page with an unsaved edit SHALL ask first. Contacts follow the same rule (A contact save names what it read).

#### Scenario: A task page saved after the sync filed it
- GIVEN a task page open on a task created on the phone
- WHEN a sync pushes the task and the page is then saved
- THEN the save lands on the task as Google now names it

#### Scenario: Both sides changed the notes
- GIVEN a task page open while a sync brings new notes for it
- WHEN the page saves different notes
- THEN the conflict page offers both notes and nothing is stored until the user picks

#### Scenario: Each side added a category
- GIVEN an event page adding "work" while a sync adds "home"
- WHEN the page saves
- THEN the event carries both categories, saved without asking

## REMOVED Requirements

None.
