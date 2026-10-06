---
cairn: change
id: incremental-enumeration
status: landed
created: 2026-09-08
---

# A sync asks what changed, in every domain

## Why

Contacts sync quickly and the other two do not, over protocols close enough that the difference has to be in what each one asks for. It is.

**Contacts ask what changed.** `sync_carddav_cards` runs an RFC 6578 `sync-collection` REPORT for `getetag` alone, carrying a sync token, and fetches bodies for the changed cards through `addressbook-multiget`. A quiet pass moves a few hundred bytes.

**Calendars ask for everything.** `list_caldav_events` runs one `calendar-query` REPORT, and its own comment says what that buys: every event's `calendar-data` comes back, so a whole calendar costs one round trip and no per-item GET. True on the first pass and wrong on every one after it, because a calendar of five hundred events re-downloads five hundred bodies whether or not a byte moved. `client/caldav.rs` has no sync token and no multiget; `client/carddav.rs` has both.

Worse, the driver throws nearly all of it away: the enumerate reads the handle and the ETag, and only the bodies the merge asks for are ever read.

**Mail asks for the last fifty of everything.** Every pass EXAMINEs each mailbox and fetches `UID ENVELOPE FLAGS BODYSTRUCTURE` for its newest fifty, whatever happened. Fifteen mailboxes is thirty round trips and seven hundred and fifty MIME trees to discover that nothing moved. Nothing is conditioned on a modseq, and `BODYSTRUCTURE`, the expensive item, is fetched for messages the app already has.

neverest, over the same libraries, asks what changed on both: `sync-collection` on WebDAV, and a QRESYNC `SELECT (QRESYNC (uidvalidity modseq))` on IMAP whose spine fetch is `UID FLAGS` and nothing more, with envelopes fetched for named UIDs alone.

## What

Both domains enumerate incrementally, from the cursor the store already keeps for them.

**Calendars get what contacts have.** The RFC 6578 driver is generic, so it moves beside the other shared coroutine runners and both DAV backends call it; `calendar-multiget` (RFC 4791 section 7.9) fetches the bodies the merge asks for. io-webdav already ships both, as `CaldavItemEnum` and `CaldavItemMultiget`; nothing had reached for them.

**Mail enumerates on a modseq.** A mailbox with a `(UIDVALIDITY, HIGHESTMODSEQ)` cursor on a QRESYNC server is selected with the QRESYNC parameter, and the server streams the changed messages and the vanished UIDs; anything else falls back to a select and a windowed `UID FLAGS` spine, which is what a first pass and a non-QRESYNC server get. The envelope fetch stops being a windowed sweep and becomes what the engine's fetch yield asks for: those UIDs, that mailbox.

**The pre-walk goes.** The mail driver cached a whole account walk because every native call was a connection, so a per-mailbox verb was a per-mailbox login. A session lasts the pass now, so the driver services `enumerate` and `fetch` as they come, which is what every other driver does and what makes the incremental round possible at all.

**The window stays.** A full round still takes the newest messages of each mailbox rather than `1:*`: this store holds a window and not a mailbox, and that is the difference between a phone and a desktop replica. A QRESYNC round reports changes across the whole mailbox, so the window drifts a little older as flags move out in it; a later full round prunes it back.
