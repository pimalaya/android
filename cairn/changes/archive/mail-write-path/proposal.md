---
cairn: change
id: mail-write-path
status: landed
created: 2026-09-03
---

# Give mail a write path

## Why

Mail is the one domain that can only be read. A message opens, and nothing that follows opening it can be done: it cannot be marked read or unread, it cannot be flagged, and it cannot be deleted. That is not a missing screen, it is a missing verb, and it is what makes the domain a viewer rather than a client. Contacts and calendars both write.

The three verbs are also the ones a reader reaches for first, and they cost one command each on both backends the app speaks. Composition is the larger half of the write path and lands separately.

## What

**Flags.** Mark a message read or unread, flagged or unflagged, from the reader. Over IMAP that is a `UID STORE` on the selected mailbox; over JMAP an `Email/set` patching the keyword. The store's flag set is updated in the same pass, so the list reflects it without waiting for a sync.

**Deletion.** Delete a message into the account's trash: the mailbox the server marks `\Trash` (RFC 6154) over IMAP, the mailbox whose role is `trash` over JMAP. A server naming no trash cannot be guessed at, so the message is marked `\Deleted` there and left where it is, which is what the protocol offers.

**Reading marks read.** Opening a message marks it read, which is what every mail client does and what the fetch deliberately does not do on its own (`BODY.PEEK[]`). Now that the verb exists, the app can make that decision explicitly rather than by omission.

The three verbs go on the reader's header card, beside the badges, as an action row.

## What this is not

No composition, no reply, no move-to-arbitrary-mailbox, no multi-select from the list. Each is a screen or a picker on top of a verb, and the verbs come first.
