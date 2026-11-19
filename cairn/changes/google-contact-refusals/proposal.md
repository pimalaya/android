---
cairn: change
id: google-contact-refusals
status: active
created: 2026-11-19
---

# Google contact refusals settled

## Why

contact-moves-carrying-edits (2026-11-11) left four loose ends, split out of guarded-contact-updates on 2026-11-19 so each lands on its own.

## What

- Dropped (owner ruling 2026-11-19): a refused Google update's conflict recorded by relisting the refused handle in the same pass instead of `mark_conflicted`. A relist reports the listing's etag, which equals the base when nothing was listed, so the engine would see no divergence; `mark_conflicted` plus the same-pass hydrate already captures the body the merge needs.
- A refused move with no listed change stops repeating every pass: it is marked refused as any refused push is (owner ruling 2026-11-19), the pending add carrying the move held under its refusal and reported, nothing retried by itself, until the user retries or drops it; the source's removal stays held until the move goes through or is dropped.
- An add carried by a book that is never synced is reported.
- Closed as not reproduced (owner ruling 2026-11-19), to reopen with a device repro: the ghost membership `card_awaited` keeps after "Drop it".
