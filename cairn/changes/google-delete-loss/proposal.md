---
cairn: change
id: google-delete-loss
status: active
created: 2026-10-11
---

# A write keeps the name, the revision and the identity the server holds

## Why

The e2e suite (cairn/changes/e2e-suite) found three data-loss bugs live on the Google test account, each a staged write silently undone:

- **Google CardDAV and CalDAV, create then delete.** An item created on the phone and pushed, then deleted on the phone, was never deleted on the server, and the next pass brought it back with nothing pushed (offline-store "The row is not resurrected", calendar "Deleting an entry").
- **Google Calendar API, create, edit, then delete.** The pass after the edit pulled the event again and the removal was dropped.
- **Google CardDAV, an edit right after a create.** It was pushed one pass late, the pass after the create pulling the card back first.

Four causes, all at the seam between the app's drivers and the servers:

1. **The created resource's name was thrown away.** io-webdav answers a create with the name the server filed it by (its `Location`, else the name asked for), and the bridge ignored it: CardDAV reported `<name>.vcf` for a name that already ended in `.vcf` (so `x.vcf.vcf` on every server), and CalDAV the name asked for. Google files a card under a name of its own, so the binding named a resource that does not exist: its delete answered 404, read as converged, and the next complete listing brought the real one back as new. On every other CardDAV server the binding was `x.vcf.vcf`, the same loss.
2. **A PUT answering no ETag left the binding with no revision.** Google's CalDAV answers no ETag to any PUT, which RFC 4791 section 5.3.4 has a server do when it stores something other than what it was sent. The engine then reads the next listing's revision as a change made elsewhere, and a removal staged before that listing loses to it: the entry is pulled back.
3. **Google Calendar's ETag came back escaped.** The bridge answers an update's ETag as a JSON string and `PimalayaClient.updateEvent` cut its quotes off rather than reading it, so Google's `"3583..."` was recorded as `\"3583...\"`, a revision no listing names: the same loss as 2, after any edit.
4. **A member whose body states another identity was read as a new item.** A card's identity is its UID, and Google rewrites a card's UID to its own name when it takes the PUT; a calendar entry's is its resource name, which a create lands under the server's choice. Once such a member moved, its fetched body named another identity under a bound handle, and the store retires the bound item (STORAGE section 10), taking with it whatever was staged on it: an edit lost, a removal never pushed, the item replaced on the phone.

## What

- **The bridge keeps the filed name.** `create_carddav_card` and `create_caldav_event` answer the name io-webdav reports, which the binding takes (SYNC section 2).
- **A write answering no ETag reads it back.** `OfflineEngine` (CardDAV) and `CalendarEngine` take the revision a create or an update landed at from the server when its answer carried none, with one `multiget` of that member; a read that fails leaves it unknown, as before, rather than failing a write that landed.
- **An update's ETag is read as the JSON string it is** (`PimalayaClient.etag`).
- **A bound member keeps its identity.** The contacts and calendar drivers name a member their source already binds by the identity the store holds, whatever its body states now (`PimdirEngine.keepBoundNames`, applied to every fetch and to a listing carrying bodies).

Out of scope: io-pimdir's own rule that a remote revision moving beats a staged removal, which is right once the revision is the server's.

## Other backends

Read for the same flaws (code and unit tests): Graph contacts and calendar, JMAP contacts and calendar and Google People name created items by the id the server answers, and answer a revision on every write (JMAP reads its own back). Graph's calendar revision is the `changeKey`, carrying no quote, so cause 3 does not reach it today; the parse fix covers any backend whose ETag carries one. Causes 1, 2 and 4 reach any plain CardDAV or CalDAV server: 1 always on CardDAV, 2 on a server altering what it stores, 4 on one rewriting a UID or relocating a create. The phone spokes assign their own handles and are unaffected by 1 to 3; 4 applies to them as to the servers.
