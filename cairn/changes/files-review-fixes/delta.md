---
cairn: delta
change: files-review-fixes
---

Amends the requirements attachments-as-files, files-tab and item-links add to `spec/mail.md`.

## ADDED Requirements

### Requirement: A file is read as its sender wrote it, whatever its size
An attachment SHALL be opened, shared, saved and exported as the bytes its part carries once its `Content-Transfer-Encoding` is undone, its charset untouched, and SHALL be copied between the message, the cache, the store and a document a buffer at a time, so its size is bounded by the storage rather than the memory.

#### Scenario: A Latin-1 text attachment
- GIVEN a `text/plain; charset=iso-8859-1` attachment
- WHEN it is saved to a folder
- THEN the saved file holds its bytes unchanged

## MODIFIED Requirements

### Requirement: A person links and unlinks items
Amends item-links: a rule's `attachment` link SHALL NOT be offered for unlinking, the file being the message's own; one removed all the same SHALL come back the next time the message's body is stored.

#### Scenario: An attachment in the Linked card
- GIVEN a message with an attachment
- WHEN its Linked card is shown
- THEN the attachment carries no unlink action

## REMOVED Requirements
