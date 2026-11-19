---
cairn: tasks
change: google-contact-refusals
---

# Tasks

- [x] Re-verify each item against the current code (2026-11-19, on io-pimdir 0.17.0 / pimdir draft-14): items 1 to 3 still applied (ContactEngine.conflictRefused still calls markConflicted; ContactEngine.pushMove answered a 412 rejected, a probe passing the move twice under `refuseAll` reading `updateCard c1 refused` again on the second pass; card_carried.sql named a carrier whatever its book's sync); item 4 not reproduced: a probe (tick into Seed amis carrying an edit, the web moving the card to a group the phone skips, two passes, "Drop it", two passes) left Jane in no book and Google holding her in "other" alone
- [-] A refused Google update relisted in the same pass (dropped by the owner 2026-11-19: a relist reports the listing's etag, which equals the base when nothing was listed, so the engine would see no divergence, and the read etag would trip the Google fetch-versus-list quirk; mark_conflicted plus the same-pass hydrate already captures the body)
- [x] A refused move with no listed change does not repeat: marked refused as any refused push (owner ruling 2026-11-19); failing test first (ContactEngineGoogleBooksTest.aRefusedMoveIsHeldRefusedNotSentAgain, the probe's scenario)
- [x] An add carried by a never-synced book reported; failing test first (ContactEngineGoogleBooksTest.anAddCarriedByABookWhoseSyncIsOffIsReported; card_carried.sql answers the carriers, UnsentContacts counts only a carrier whose book is subscribed with its server sync on)
- [-] The ghost membership after "Drop it" dropped (closed as not reproduced by the owner 2026-11-19, to reopen with a device repro)
- [ ] Spec folded, log written, change archived
