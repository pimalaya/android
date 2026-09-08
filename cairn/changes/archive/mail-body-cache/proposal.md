---
cairn: change
id: mail-body-cache
status: landed
created: 2026-09-08
---

# Keep an opened message, instead of fetching it again every time

## Why

Opening a message fetches it. Opening the same message again fetches it again, and so does opening it a third time, on the plane, with no network, where it fails. The store holds the spine of every mailbox and nothing else, so the reader has nowhere to look before reaching for the server.

That was honest while bodies had nowhere to live, and they have somewhere now: an item's object, with the level ladder (0 probed, 1 meta, 2 full) saying which items carry one. The plan of record has said so since M3, meta for all and full on open, and the mirror is already writing items that only ever stay at meta.

## What

**An opened message is stored.** The reader asks the store first and renders what it finds, with no network and no spinner; a message it does not hold is fetched, filed as the item's object, and rendered from the same bytes. The item goes to full when its body lands, and back to meta only by being replaced. A refresh no longer wipes what was cached: a mirror row carrying no body keeps the one already stored, the way it already keeps the summary it does not restate.

**The bridge returns the message, not a rendering of it.** `fetchMessage` resolved the MIME tree server-side and handed the reader a body it could not store; it becomes `fetchMessageSource`, which answers the RFC 5322 bytes, and `parseMessage`, which turns those bytes into what the reader draws and touches no network. The reader therefore renders a cached message and a freshly fetched one through the same call. The bytes cross JNI base64-encoded, because a Java string is UTF-8 and a message is not: what is stored is the message as the server sent it, so a body in a legacy charset still decodes correctly on the way to the screen.

IMAP already fetched the whole message (`BODY.PEEK[]`) to resolve it. JMAP did not: it asked for body values, capped. It now reads the `blobId` and downloads the message, which is one round trip more and, for a message carrying attachments, more bytes than the body values alone. That is the cost of holding the message rather than a rendering of it, and offline reading is what the app is for.

Attachments stay where they are: named and measured from the stored message, their bytes not downloaded separately, because they are already in it.
