---
cairn: log
change: mail-write-path
landed: 2026-09-03
---

# Give mail a write path

Mail can be written to now. Three verbs, on both backends: the markers, marking read on open, and deletion into the account's trash.

The IMAP session keeps the capabilities its authentication already returned, which is what lets the write verbs choose rather than guess: MOVE (RFC 6851) where the server has it, a COPY plus a `\Deleted` marker where it does not. LIST now carries its attributes so the trash is the one the server marks `\Trash` (RFC 6154), rather than a name matched against a list of words in a handful of languages. Nothing is expunged either way, an expunge without UIDPLUS being mailbox-wide.

The JMAP half is one `Email/set` per verb, with the three markers RFC 8621 section 4.1.1 maps and no fourth: `\Deleted` has no keyword there, deletion being a move, so an account naming no trash fails rather than being handed a keyword nobody defined. A per-object refusal is read out of `notUpdated` and raised, since the method itself succeeds while refusing the one change it carried.

On the app side the store mirrors what the server accepted, never the other way round: the marker is written on the server first, and only a move takes the message out of the list, a marked-deleted one staying where it is and saying so. The reader's header card gained an action row with the two toggles and the delete button.

Spec updated: `mail` (ADDED: a message's markers can be written, opening a message marks it read, a message is deleted into the account's trash).
