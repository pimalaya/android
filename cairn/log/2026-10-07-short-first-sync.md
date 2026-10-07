---
cairn: log
change: short-first-sync
landed: 2026-10-07
---

# A short first sync: the newest chunk of each mailbox, one domain per tab

Capabilities moved: mail (added: a mailbox is listed a chunk at a time, the merged list reaches down to its mailboxes' floor, older mail fills in behind, a pass takes the inbox first, each page's time is logged; modified: a mailbox is stored whole, an account bounds its mail, the mail list loads lazily), offline-store (added: a load carries each message's date), onboarding (modified: a connected account is synchronised before the app shows it).

io-pimdir moves to f9b13f8, which stores an undated message's empty `Date` as `NULL`.

**Bridge.** `Native.mailFloor` answers a chunk's floor, the oldest `Date` among the `count` newest messages below a ceiling, null when fewer lie there (`listing::Floor`, a typed reply): IMAP searches below the ceiling (`SENTBEFORE` a day above it) and reads headers newest UID first, Graph lists `/messages` ordered by `sentDateTime desc` with `$top` and `sentDateTime` alone selected, Gmail lists ids with `before:` and reads their metadata (kept on the session for the round that follows), JMAP queries by `receivedAt` and reads `sentAt`. Every backend names the mailbox roles in pimdir's vocabulary: IMAP `INBOX` and the RFC 6154 attributes, the RFC 8621 roles, Gmail's system labels, Graph's well-known folders (four more GETs per roster on Graph). A load's `date` becomes a date-only summary that the driver never writes back.

**A bug the chunks exposed.** The store's state-only load carried no summary, so the engine read every loaded message as undated, which is in every scope: a round opened and closed by one page found every message it did not list absent, the ones above a widening's band among them, and retired them. Any single-page band (a widened bound since full-mail-index, every scroll widening now) did it. The load now carries each message's `Date`.

**App.** `MailEngine.sync` lists a mailbox never listed from its first chunk's floor, else from the round under way or the coverage, so a later pass is a delta; `widen` takes the next chunk below the coverage's floor. Passes take the mailboxes by role, the inbox first, the roles stored through `set_collection_role`. `MailStore` reads each mailbox's edge (role, whether listed, the floor that limits the list) and cuts the list's count, per-day counts and pages at the most recent shown floor, around the canonical statements; a search is not cut. `MailList` ends on a row that widens the shown mailboxes holding the limiting floor by 50 (`MailFill.limiting`), or says older mail needs the network. `MailFill` steps the background fill, 500 a chunk, the inbox and the sent mail first, while `MainActivity` keeps it to the foreground, an unmetered network and no other sync, one chunk per task on the io thread. `FirstSync` keeps what each domain of a freshly connected account owes, paid by its tab's first visit: the flow lands on mail, whose dialog waits for the first chunks alone.

**Measured.** Per page, the network, the JSON, the engine and the store are logged. On the host, a first round of 4,000 messages without network: JSON 60 ms, engine 153 ms, store 1,220 ms before; 55 to 63, 182 to 203 and 987 to 1,006 ms after. The store dominating and Rust having no SQLite on Android, its writes were cut: about 13 statements per new message down to 7 plus its addresses, compiled single-value reads, the bind rewrite cached, a larger statement cache (docs/performance.md).

**Not done.** Graph widens by relisting the wider scope: its delta link is bound to the filter it was made under, and listing the band by plain `/messages` would leave the delta blind to it until pimdir keeps a checkpoint per band. Nothing was measured on a device.
