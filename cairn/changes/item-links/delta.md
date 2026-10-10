---
cairn: delta
change: item-links
---

Folds into `spec/mail.md` beside the files requirements, until a capability for links is split out.

## ADDED Requirements

### Requirement: Every item page shows its links
The message reader, the contact page, the calendar entry page and a file's sheet SHALL show the item's links in a Linked card: every reference it makes and receives (pimdir STORAGE §14.2), sorted by the other item's kind, each naming the other item, its kind and why it is linked (*Sent by*, *Attachment*, *Invitation* or *Related* for a rule's link, *Linked by you* for a person's). Tapping one SHALL open the other item on its own page. A message still in an outbox, a contact or an entry being composed, and a contact being resolved SHALL show none. The reader and the file sheet SHALL open the card in a dialog, the reader's badge counting the links.

#### Scenario: A message and its attachment
- GIVEN a message whose body is stored, attaching `report.pdf`
- WHEN its Linked card is opened
- THEN it lists `report.pdf`, a file, as its attachment, and tapping it opens the file's sheet

### Requirement: A person links and unlinks items
The Linked card's *Link to* SHALL open a picker searching messages, contacts, calendar resources and files by title, a few of each; picking one SHALL record a reference of role `related` and origin `user` from the item to it. Unlinking SHALL remove the reference after asking, and say for a rule's link that it may come back while its rule matches.

#### Scenario: Linking a contact to a message
- GIVEN a message from Alice and Alice's contact
- WHEN the message is linked to the contact from the picker
- THEN both pages list the other, *Linked by you*
- AND unlinking from either page removes it from both

## MODIFIED Requirements

## REMOVED Requirements
