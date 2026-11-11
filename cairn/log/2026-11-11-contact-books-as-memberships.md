---
cairn: log
change: contact-books-as-memberships
date: 2026-11-11
---

# Contact books as memberships, held changes reported

Found while recording the Google verification video (docs/providers.md, 2026-11-11): adding a contact created in the app to another Google label failed with "People API returned HTTP 400: Resource name "people/<uuid>" is not a valid contact person resource", and moving a contact from Contacts to a label replaced the person on Google (14-contacts.mp4, the avatar changes colour, the old page "Contact not found").

Root causes:
- ContactEngine named the card by the store's link id in label patches and batch deletes (pushAdd, pushGoogle, pushRound); for a card created on the phone that is the app's UID, which io-gpeople keeps in its clientData stash.
- The app's origin query excluded a tombstoned holder (`i.deleted = 0`), so a move's add was planned as a create; and the removal plan read the placement's own tombstone flag, true on every staged removal, so every removal on Google and JMAP was a delete: a move deleted the person and created another, and unticking one label deleted the person from the server. A data loss in the released 0.2.0.

What changed (worked by one agent, reviewed by another over five passes; each pass's probes became tests):
- Every push names the card by its server handle; a membership add by the handle of a book that holds it.
- Origins follow pimdir's origin_for_link (body-checked, a tombstoned holder accepted, `dup:` keys), scoped by the roster's account and kind (card_bound_elsewhere.sql); card_elsewhere.sql gives the live, held and leaving facts; the push plan gains a `held` fact and a HOLD action.
- A move stages the target first, then the source leave with `supersede`, held until the add lands; ContactWriter places before it removes. An accepted update or patch takes the card out of the books it leaves (`leaveWith`, also on the unguarded retry and JMAP batch rounds). A book that took a card is relisted in the same pass. A pure tick copy follows a pull of its source (card_copies.sql).
- An add whose body differs from every server copy is held, never patched without its body, never created twice, never written over the server. UnsentContacts computes the held adds and held removals from the store (card_pending.sql, card_unsent.sql, superseding_collection.sql); SyncCoordinator.offerUnsent shows them in a "Not sent yet" dialog; ContactsList marks the row with `contact_diverged`. No sync state lives in SharedPreferences.
- Unticking the only copy of an unsent change asks "Keep the change?": keeping a sent card's change merges three ways against its binding base (ContactClient.mergeConflictForm), opening the conflict form on a collision; a never-sent copy opens the form; dropping leaves it in Deleted items. detachToLocal keeps the copy holding an unsent change.
- Tests failing first: ContactEngineGoogleBooksTest (29) and ContactEngineJmapBooksTest (4) on HEAD: the 400 on `people/uuid-1`, `deleteCard c1, createCard c2` for a move, the untick revived, the edit lost or overwritten in the reviewer's probes (A to H, Q1 to Q4). ContactEnginePushTest and OfflineClientTest follow correct behaviour (server handles; a card in two books before one is removed).
- Checked by the agent: app 942 tests, client 14, Rust 645, clippy and fmt; merged here with the day's other fixes and run again.

Left for contact-moves-carrying-edits: delivering a held add's body (the Google update retries a stale etag on its own), saveFanOut staging an edit on one replica, a tick creating a duplicate when the web moved the card to an unsubscribed group (also before), a withdrawal outside the editor, an untick right after a revision move revived, a false positive in the report after a partial pass, UI tests for the dialogs.

Capabilities moved: carddav-sync (A card is pushed by its server handle, A card moves between books by its memberships, An add the server cannot take whole is held and reported, Unticking a book never drops an unsent change silently, added).
