---
cairn: change
id: deleted-items
status: landed
created: 2026-10-08
---

# Deleted items: one list in settings to restore or free space

## Why

pimdir never loses what a user deleted: when an item's last binding goes, its row and body are retained, hidden from live reads, listed in the store's trash view until an explicit purge (OVERVIEW, STORAGE §11). A completed move leaves nothing retained (`held_elsewhere`). The app exposes none of it: nothing can be restored, nothing purged, and the space retained grows unseen.

io-pimdir has the reads and the purge: `list_retained(collection, after, limit)` (items with summary, object, level, retention at/by/size), `count_retained`, `retained_bytes`, `purge`, `purge_retained_before`, `collect_garbage` (io-pimdir/src/client/reader.rs:948-984, client.rs:361-464).

## What

**Settings > Deleted items**, outside the main screens, one list for all domains, newest deletion first.

- **A row** shows the item's summary (mail subject and sender, contact name, event title and date), its domain, account and last collection, and when it was deleted. A row a source still binds (`retained_at` empty: the server delete is not carried out yet) says "waiting for the server" and is neither restorable nor purgeable.
- **Restore** is offered only for an item whose body the store holds (level `Full`): contacts and events always, mail only once opened or downloaded. Restore asks where to put it back, a picker of the account's collections of that kind with the last collection preselected, and stages an `Add` of the stored body there: back in its last collection it revives the retained row (STORAGE §11.1), elsewhere it is a new item; the next sync uploads it. A mail held as summary only shows "not stored on this device".
- **Free space** at the top: what a purge releases (`retained_bytes`), one button purging every retained row then collecting garbage. Confirmed by a dialog.

The listing walks `list_retained` per collection; a store-wide read is added to io-pimdir only if that proves slow.

## Out of scope

Automatic purge on a schedule (pimdir leaves retention to the owner; a later setting), searching the list.
