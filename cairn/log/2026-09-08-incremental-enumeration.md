---
cairn: log
change: incremental-enumeration
landed: 2026-09-08
---

# A sync asks what changed, in every domain

Contacts synced quickly and the other two did not, over protocols close enough that the difference had to be in what each one asked for. It was.

**Contacts asked what changed.** An RFC 6578 `sync-collection` REPORT for `getetag` alone, carrying a sync token, and bodies fetched for the changed cards through `addressbook-multiget`. A quiet pass moved a few hundred bytes.

**Calendars asked for everything.** One `calendar-query` REPORT, and its own comment said what that bought: every event's `calendar-data` came back, so a whole calendar cost one round trip and no per-item GET. True on the first pass and wrong on every one after, because five hundred events came back to report that none of them moved. Worse, the driver threw nearly all of it away: the enumerate read the handle and the ETag and only the bodies the merge asked for were ever used. `client/caldav.rs` had no sync token and no multiget; `client/carddav.rs` had both.

**Mail asked for the last fifty of everything.** Every pass EXAMINEd each mailbox and fetched `UID ENVELOPE FLAGS BODYSTRUCTURE` for its newest fifty, whatever had happened. Fifteen mailboxes was thirty round trips and seven hundred and fifty MIME trees to discover that nothing had changed.

**The RFC 6578 driver was generic all along.** It only ever had one caller, so it lived beside the card verbs; it now sits with the other shared coroutine runners and both DAV backends call it. `calendar-multiget` reads the bodies the merge names. io-webdav had shipped `CaldavItemEnum` and `CaldavItemMultiget` the whole time and nothing had reached for them.

**Mail enumerates on a modseq.** A mailbox with a `(UIDVALIDITY, HIGHESTMODSEQ)` cursor on a QRESYNC server is selected with the QRESYNC parameter and the server streams what moved and what went; anything else falls back to a select and a windowed `UID FLAGS` spine, which is what a first pass, a rebuilt handle space and a server without the extension all get. A new UIDVALIDITY under the cursor is read as the handle-space reset it is and answered with a full round rather than a delta describing a mailbox that no longer exists. The envelope fetch stopped being a windowed sweep and became what the engine's fetch yield asks for: those UIDs, that mailbox.

**The pre-walk went.** The mail driver cached a whole account walk because every native call was a connection, so a per-mailbox verb would have been a per-mailbox login. The session lasts the pass now, so the driver services `enumerate` and `fetch` as they come, which is what every other driver does and what makes the incremental round possible at all. One LIST still names the mailboxes, because the roster is one question.

**Two things worth knowing.** The window stays: a full round takes the newest messages of a mailbox rather than `1:*`, this store holding a window and not a mailbox, and a delta round reports changes across the whole mailbox so the window drifts a little older until a full round prunes it. And the VEVENT filter went with the `calendar-query`: a `sync-collection` has no component filter, and the app wanted the others anyway, since the expander places a to-do and a journal entry on a day like anything else with a date and the entry page already edits all three. The filter was the one place that disagreed.

**JMAP is honest about what it does not do.** Neither `Email/changes` nor `CalendarEvent/changes` is wired, so a JMAP mailbox and a JMAP calendar answer whole rounds and carry no cursor. What they no longer do is answer twice: the session holds the round it read, so the fetch beside it costs nothing.

Capabilities moved: mail, calendar, offline-store.
