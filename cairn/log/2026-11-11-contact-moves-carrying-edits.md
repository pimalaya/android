---
cairn: log
change: contact-moves-carrying-edits
date: 2026-11-11
---

# Contact moves carrying an edit, and the gaps left by contact-books-as-memberships

Approved by the owner the same day; worked by one agent, reviewed by another over three passes, each probe a test.

- Guarded Google update: `Gpeople::update_card` (rust/src/contact/gpeople.rs), on a missing or refused etag, re-reads the person and merges per People field against the base: a field the server already holds as the edit sets it is not sent, the others go under the fresh etag, a field both sides changed differently comes back as a 412 and is not written. `retry_unguarded` (rust/src/contact/sync.rs) retries unguarded on CardDAV only. An update refused with no listed change after it is marked conflicted at the listed revision (mark_conflicted.sql, an owner write as SET_CONFLICT_REVISION is; STORAGE §8), the next hydrate reading the server body for the merge or the form. The per-field merge is coarser than vcard-rs's (two added phones, ORG against TITLE come back as a conflict the next listing merges), costing one pass.
- A move carrying an edit is delivered on Google: `PushAction.MOVE` updates the superseded card under its base, then patches its memberships (ContactEngine.pushMove); a conflict keeps it held. JMAP moves carrying an edit stay held and reported.
- ContactWriter.saveFanOut stages the edit on every book copy whose body equals the edited copy's, leaving a copy with its own unsent change alone.
- A card the account's listing moved out of a book stays listed there while another book stages its add (card_awaited.sql), so the add is a membership patch, not a second person.
- A staged removal whose card is listed with a body already agreed on the account, the superseding add's included, is read at its base revision, not revived (card_leaving.sql); an edit made elsewhere still revives it. Closes a half-landed move (update in, patch failed) reviving the source.
- A deletion of the only copy of an unsent change made in the phone's own Contacts app waits on the phone (PhonePass.deferred, PimdirContacts.withdrawalWaits) and is reported in "Not sent yet", read from the phone's deleted raw contacts (PhoneSpoke.deleted).
- An add carried by another book's unsent edit is not reported (card_carried.sql); a carrier refused repeatedly turns conflicted and so stops hiding it.
- Tests failing first: the Rust gpeople guard tests (the edit written over), ContactEngineGoogleBooksTest's move, Q2b, untick and refusal cases, ContactPhoneDeletionTest, ContactWriterGoogleTest (both dialogs); FakePeople honours the etag guard.
- Checked: app 976 tests, client 14, cargo 651, clippy and fmt, in the agent's worktree; merged here and run again.

Left (change guarded-contact-updates): JMAP and Graph updates unguarded between listing and push; the conflict recorded through a same-pass relist of the refused handle (exact revision and body) instead of mark_conflicted; a refused move with no listed change repeating one read and one refused update a pass until its source is listed (reported as a waiting move); card_carried missing a report when the carrier's book is never synced; a ghost membership kept by card_awaited after "Drop it" until the next full round.

Capabilities moved: carddav-sync (A card moves between books by its memberships, An add the server cannot take whole is held and reported, Unticking a book never drops an unsent change silently, modified; A contact update never writes over a remote change, An edit reaches every copy of the contact, added).
