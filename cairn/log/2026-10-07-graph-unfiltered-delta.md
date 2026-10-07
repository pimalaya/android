---
cairn: log
change: graph-unfiltered-delta
landed: 2026-10-07
---

# Graph mail: one unfiltered delta link, mail listed by band

Capabilities moved: mail (added: a Graph mailbox keeps one unfiltered delta link; modified: a mailbox is stored whole, a mailbox is listed a chunk at a time).

short-first-sync left Graph widening by relisting the wider scope, its delta link being bound to the `receivedDateTime` filter it was made under: about `n²/1000` summaries to fill a mailbox of `n`. Graph mail is now bound to no scope, as IMAP, Gmail and JMAP are (`scope_bound` false), so io-pimdir runs band rounds for widenings and keeps the checkpoint.

**Bridge.** A folder's listing is one piece of logic (`list_folder`) over three Graph requests (`GraphFolder`): `/messages` over a band of `sentDateTime` (`ge` the floor, `lt` the ceiling, newest first, the summary `$select`, `$top` 1,000), the folder's message delta made with no filter (`$select=id,sentDateTime,isRead,flag`, `Prefer: odata.maxpagesize=1000` on every request), and messages read by id (`$batch` of 20, a GET the batch did not serve sent again alone). A first chunk and a band round list by `/messages` and hand no checkpoint; a band page resumes below the oldest `Date` reached, that second included and its messages already listed skipped (`$skip` within one second only when a page of it is all listed), never by Graph's `$skip` next link, which loses a message whenever one above it goes mid-listing. A round over a scope the store covers (`covered`, sent by the driver) walks the delta's first pass, a Graph page a round page resumable from the next link, the members in scope listed by id and markers, the delta link on the last page as the checkpoint (`graph-delta:` before it). A delta drops a change dated out of scope, applies a removal whatever the date, and lists a change in scope by id and markers alone (`Named::unnamed`). A link made under a filter, and a round cursor from before, are refused for the next pass to make the unfiltered link. `Native.nameMessages` reads unnamed members by id.

**First-sync ordering.** The dialog's round lists the first chunk by `/messages` and closes with no checkpoint; no delta is asked for. The next pass finds the scope covered and no checkpoint, and io-pimdir opens a round over it, which makes the link. That round is the folder as it ends, so a message of the chunk deleted, moved or read before it, and one arrived, are read there, a deletion as an absence from a round whose stamps are its own. One round for both (the band, then the first pass) was rejected: a message stamped by a band page and deleted before the first pass reached it would never be found absent, nor reported by a delta that never named it.

**App.** `MailEngine` sends `covered` with every listing, names a delta's members by the store's binding or by `nameMessages` (one gone since is left out), and runs every backend with `scope_bound` false.

**Tests.** Over a fake Graph through io-pimdir's std engine and store (a dev-dependency, the bridge itself compiling no SQLite): the band filter's exact bounds; the first chunk landing with no delta asked, the next pass naming the 300-message folder by id once, then one request a pass; three widenings listing 200 messages for 200 stored, the folder's ids once; a delta dropping out-of-scope changes and reading only the new message in scope; a deletion, a read and two arrivals between the chunk and the link; an interrupted first pass resumed from its page; an expired link; one second's messages across pages with a removal mid-listing; a filtered link refused. `MailNamingTest` covers the driver's naming and `covered`.

**Not done.** An undated message bound by the store is dropped when a band round closes on io-pimdir f9b13f8 (a band listed by date never lists it); io-pimdir is being fixed, and `an_undated_message_survives_a_widening` pins it, ignored until the bridge moves to the fix. Nothing was measured on a device or a live tenant.
