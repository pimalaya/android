---
cairn: delta
change: automatic-references
---

Folds into `spec/mail.md` after attachments-as-files, files-tab and item-links, whose requirements it amends where noted.

## ADDED Requirements

### Requirement: Items are linked by rule
The app SHALL record pimdir's automatic references by its canonical statements (STORAGE §14.2): after a mail's summary is written, a reference to every card holding its `From` address (`sender`); after a card is written, the same from every mail its addresses sent; when a message's body is stored, its invitation (the first `text/calendar` part's `UID`) recorded in its summary and a reference to the calendar item it names (`invitation`); after an event or a task is written, the same from every mail inviting to it. No reference SHALL name an item under a writer-derived key. A store written before these rules SHALL be reconciled once on open: every held message's invitation derived, every rule run over the whole store.

#### Scenario: A card added after the mail
- GIVEN mail from alice@example.org and no card for her
- WHEN a card with that address is saved
- THEN each of those messages lists the card, *Sent by*

#### Scenario: An event synced after its invitation
- GIVEN an invitation opened before its event synced
- WHEN the event syncs
- THEN the invitation lists the event, and the event the invitation

### Requirement: A window moved later says what it frees
The window picker SHALL say, for a day later than the window, how many messages held on the phone and how many megabytes leave it (`sum_mail` with `held` 1); for a day earlier, what a download fetches (`held` 0), each message counted once however many mailboxes file it.

#### Scenario: Moving the window a month later
- GIVEN 120 messages held on the phone in the month after the window
- WHEN the picker is set a month later
- THEN its line says 120 messages and their size leave the phone

## MODIFIED Requirements

### Requirement: The attachment mark is corrected by the body
A listing SHALL mark a message as carrying an attachment from the source's own flag where it states one (Graph `hasAttachments`, JMAP `hasAttachment`), else when its top-level `Content-Type` is `multipart/mixed`. Storing the message's body, by an open or the body step, SHALL restate its summary from the body: the mark the walk of its parts gives, its size and its invitation.

#### Scenario: A list footer
- GIVEN a `multipart/mixed` message carrying no attachment, listed with a paperclip
- WHEN it is opened
- THEN its row loses the paperclip

### Requirement: A stored body records its attachments as files
Amends attachments-as-files: the parts recorded SHALL be those carrying `Content-Disposition: attachment`, the parts the mark counts; a message under a writer-derived key SHALL get none; the attachments collection SHALL carry the role `attachments`, which is what tells it from a folder.

#### Scenario: An inline logo
- GIVEN a message with an inline image and an attached PDF
- WHEN its body is stored
- THEN the PDF alone is recorded as a file

## REMOVED Requirements
