---
cairn: change
id: drop-sync-mark
status: active
created: 2026-10-12
---

# Drop the per-row sync mark

## Why

Mail, contact and agenda rows carried a cloud mark while a staged change waited for a push (grey, *Waiting to sync*) and once the server refused it for good (red, *Refused by the server*). Users found it confusing: it flickered on every local action, said nothing they could act on, and a refusal is rejected again by every sync anyway. It also cost a store read per mail row on every page.

## What

- Remove the mark from the message, contact and event rows, its strings and its icon, and the *Refused by the server* state a message row's line took.
- Remove what only fed it: the per-row "owes a push" reads (`PimdirStorage.unsynced`, the binding columns the contact and event lists read for it), the `unsynced`/`refused` fields, and `Refusals`, the app's record of refused changes beside the store, with its writers in the engines.
- Keep everything else: a refused change is still rejected on the wire, the outbox row still says *Waiting to be sent*, *Refused by the server* or *May have been sent*, a message marked `\Deleted` still says so, conflicts keep their mark, and a row not fully downloaded keeps its dim.
