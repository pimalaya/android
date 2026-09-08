---
cairn: log
change: mail-body-cache
landed: 2026-09-08
---

# A message opened once is a message the store holds

Opening a message fetched it, and opening the same message again fetched it again. The store held the spine of every mailbox and nothing else, so the reader had nowhere to look before reaching for the server, and no answer at all with no network.

It has somewhere now, and always had: the item's own object, with the level ladder saying which items carry one. What the reader fetches is filed against the envelope it was opened from, the item goes to full, and every later open is a read of the store. Nothing invalidates it, a message being immutable once sent, and a refresh no longer wipes it: a mirror row carrying no body now keeps the object already stored, on the same terms as the summary it does not restate, and an item's level follows what it actually holds rather than always reading full.

## The bridge returns the message, not a rendering of it

`fetchMessage` resolved the MIME tree on the bridge side and handed back a body the app could not store. It is now `fetchMessageSource`, which answers the RFC 5322 bytes, and `parseMessage`, which turns bytes into what the reader draws and opens no socket. The reader therefore renders a cached message and a freshly fetched one through the same call, and the parser moved out of the IMAP client into the mail module, where it belongs: resolving a MIME tree is not a protocol operation, and it now serves both backends and the store.

The bytes cross JNI base64-encoded in the reply, and back as a byte array in the argument. A Java string is UTF-8 and a message is not: decoding one leniently on the way through would have replaced every byte of a legacy-charset body before the parser that reads the header naming that charset ever saw it. What the blob holds is the message exactly as the server sent it.

## What it costs JMAP

IMAP already fetched the whole message: `BODY.PEEK[]` is what the MIME tree was resolved from. JMAP did not, asking for decoded body values capped at half a megabyte, so this is a real change there: an `Email/get` for the `blobId`, then the RFC 8620 section 6.2 blob download, which is one round trip more and, for a message carrying attachments, more bytes than the body values alone.

That is the price of holding the message rather than a rendering of it, and it is paid once per message instead of once per open. The download URL is the session's RFC 6570 template with its four variables filled in and percent-encoded, a blob id being opaque and free to carry a slash; two tests pin that, because a variable left unfilled reaches the server as a path with a brace in it and comes back a 404.

Attachments still travel as names and sizes rather than bytes, and they now come from the stored message rather than from a server's listing of it, which is the same answer derived from what the reader actually holds.

Capabilities moved: mail, offline-store.
