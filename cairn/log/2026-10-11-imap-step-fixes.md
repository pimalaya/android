---
cairn: log
change: imap-step-fixes
landed: 2026-10-11
---

# IMAP step fixes: bodies linked across mailboxes, an honest floor

Capabilities moved: mail (added: a link id names a message across mailboxes only where the account lists them as one, an IMAP step widens from the account floor; modified: a mailbox is listed a chunk at a time, bodies download behind the list; removed: an IMAP step widens from the inbox floor).

**Why.** The e2e suite on google-imap. A message's link id is its handle (`Named` in rust/src/client/listing.rs), an IMAP UID, and `PimdirStorage.lookupObjects` answered it from any collection of the store: once `ImapStep` had read `[Gmail]/All Mail`, the other mailboxes linked their UIDs to All Mail's bodies of the same number (the scratch mailbox's UID 1 showed a billing notice). The engine's size witness did not catch it, a load carrying a message's date and no size. Separately, without `SORT` the floor was the oldest timely `Date` among the 50 newest UIDs; Gmail's imported mail is received when dated, so it fell to 2025-04 and the first step listed 1,386 messages and read about 730 bodies.

**Identity.** Kept: a message's link id is its handle. On Gmail and JMAP (accounts listed account-wide) it names one message across mailboxes; on IMAP it names one message in its mailbox alone, and on Graph one folder's copy. `PimdirEngine.lookup` (new hook, the store-wide `lookupObjects` by default, which calendars keep) is overridden by `MailEngine`: `lookupObjects(links, accountId)` on an account-wide account, nothing otherwise, so every IMAP and Graph body is fetched. Rekeying IMAP on `Message-ID` or `X-GM-MSGID` was weighed and left: every mail path relies on the handle being the link id, and every IMAP store would need a migration.

**Repair.** `MailStore.repairLinks`, once per account not listed account-wide, from `MailEngine.mailboxes`: `releaseCrossLinked` frees every body of the account's mail held under a link id another collection holds with the same hash, both sides, on `release_before`'s terms (no conflict, a base, no local edit), bases first, then refcounts recomputed and the collector run. The body step reads them again.

**Floor.** `Floor` keeps the `count` newest dates (`offer`, any order; `take`, the source's order, as before for `SORT`, Graph and JMAP) and replies with them (`dates`); `take_received` and `SKEW_HOURS` are gone. Without `SORT`, `ImapSession::sampled_floor` reads the newest UIDs 50, then doubling, at most `SAMPLE_WIDENINGS` (4) times, 800 headers at most, until a part adds nothing dated or received at or above the sample's 50th newest `Date`.

**Account floor.** `ImapStep`'s first step reads each stepped mailbox's 50 newest dates (`MailEngine.newest`, `PimalayaClient.mailNewest`) and takes the 50th newest of all as the floor and frontier, null when they hold 50 or fewer in all: about 50 bodies, where the inbox's floor brought the inbox's 50 plus every other mailbox above it.

**Refused headers.** Gmail answers a `UID FETCH` of header fields with `NO Some messages could not be FETCHed` when a message of the set went meanwhile (a test's cleanup in All Mail), which failed the first step through `mailNewest`. `ImapSession::fetch_header_fields` now halves a set refused with NO until the gone UID is isolated and left out; `attempt` keeps a coroutine's refusal apart from the wire's failure (`run` is built on it).

**Tests.** Rust (454 green, clippy clean): listing.rs (a sample keeps the newest by date whatever its order, the reply's dates), imap_tests.rs (no-`SORT` floor is the 50th by date; a header fetch refused for a gone message reads the others; a settled sample reads two chunks; imported mail among the newest UIDs widens the sample to 400; the sample is bounded at 800). Java: `ImapStepTest` (the first step's account floor, 37 inbox and 13 sent; two mailboxes sharing UIDs keep their own bodies, which fails with the store-wide lookup; a cross-linked body released once on both sides and read again). e2e: `E2eMailStepTest` checks an IMAP scratch mailbox against the account's frontier, and a filtered Load more against the mailbox's own frontier (`MailWindow.since(context, accountId, collection)`), which is what it moves; it read the account's, passing only while the inbox floor lay further back.

**Left open.** References and attachment stand-ins keyed by a message's link id (`FileStore.partKey`, `item_reference`) and the store-wide `seq` are still shared by two IMAP messages of one UID in two mailboxes (or two accounts); that wants IMAP link ids keyed on `Message-ID` / `X-GM-MSGID`. A load still carries no size, so the engine's witness stays blind (Gmail's `sizeEstimate` is not the octets). The first IMAP step reads one floor per stepped mailbox.
