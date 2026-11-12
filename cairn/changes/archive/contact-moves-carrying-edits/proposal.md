---
cairn: change
id: contact-moves-carrying-edits
status: landed
created: 2026-11-11
---

# Contact moves carrying an edit, and the gaps left by contact-books-as-memberships

## Why

contact-books-as-memberships (2026-11-11) made a move between books on Google and JMAP a membership patch, with the source held until the target lands, and holds (and reports as "Not sent yet") an add whose body differs from every copy the server holds, rather than patching it without its body or overwriting the server. Five reviews of that change left these, each found by a probe:

- A held add's body is never delivered: an edit saved together with a move waits, reported, until the user resolves it. Delivering it needs a guarded update first, and the bridge's Google update retries a stale etag with a fresh one (rust/src/contact/gpeople.rs around 323), which defeats any guard.
- ContactWriter.saveFanOut stages an edit on one replica per account-level card, the first by collection id, often the label (Google group ids sort before `myContacts`): the root of the held adds.
- A tick while the web moved the card to a group the phone does not subscribe to creates a second person (probe Q2b). HEAD did the same.
- A withdrawal made outside the editor (deleting the label's copy in the phone's Contacts app) asks nothing, where the editor asks "Keep the change?".
- Unticking one of a card's other books right after a patch or an edit moved its revision is revived by the next listing.
- The "Not sent yet" report shows a false positive after a pass that skipped the book whose edit an add waits for, until the next full pass.
- No UI test covers the "Not sent yet" dialog or the untick question.

## What (to decide)

- Moves carrying an edit go through the engine (a destination or `supersedes` on contact tombstones, pimdir STORAGE §15.3), so the edit becomes a derived update the listing checked; the Google update stops retrying a stale etag on its own.
- saveFanOut stages the edit on every replica of an account-level card, or on the one the server binds.
- An add whose card the account holds outside the subscribed books is held and reported, never created again.
- The phone's Contacts app withdrawal asks through a notification, or the mirror refuses it.
