---
cairn: log
change: mail-load-more-imap
landed: 2026-10-11
---

# Load more: the IMAP step

Capabilities moved: mail (added: an IMAP step widens from the inbox floor, an IMAP search is kept while its mailbox stands; modified: a mailbox is listed a chunk at a time, bodies cross the bridge as files; intro). The live check and the device test are pending, so the change stays active.

**Why.** docs/mail-window-files-references-plan.md B.13, IMAP bullet: an IMAP floor was the oldest `Date` among the 50 newest UIDs, so one imported message dated years back pulled a whole mailbox in, and each body cost one `UID FETCH` round trip.

**Floor.** `ImapSession::floor` is an order statistic: on a server advertising `SORT`, `UID SORT (REVERSE DATE) UTF-8 SENTBEFORE <ceiling + 1 day> SENTSINCE <ceiling - 90 days>`, widened to a year then all (`SORT_WINDOWS_DAYS`), header fields read in that order until 50 dated ones lie below the ceiling; without `SORT`, the newest UIDs with `INTERNALDATE` fetched beside the header fields, `Floor::take_received` counting a message dated more than `SKEW_HOURS` (24) before its reception toward the chunk without lowering its floor (the oldest of all when every one is such). The floor's own `Floor::take` is unchanged for Graph and JMAP.

**Cache.** `ImapState.cache` replaces the single `searched` entry: per mailbox and UIDVALIDITY, the UIDs each `SEARCH` scope or `SORT` window found, and the header fields read (`Header`: markers, summary, `INTERNALDATE`), dropped when an EXAMINE or QRESYNC SELECT (`ImapState::observe`) reports another UIDNEXT, HIGHESTMODSEQ or EXISTS, header fields kept only where a HIGHESTMODSEQ is reported, everything dropped on a write SELECT. A round after a floor reads no header field again.

**Bodies.** `ImapSession::fetch_sources` (the `is_imap` branch of rust/src/ffi/mail.rs `fetch_sources`): `UID FETCH <set> (UID RFC822.SIZE)`, then `batches` of at most `BODY_BATCH` (50) messages and about `BODY_BATCH_BYTES` (12 MB), a message past it alone, each one streamed `UID FETCH <set> (UID BODY.PEEK[])` through io-imap 0.7.1's `ImapMessageFetchStreamBatch`, bodies written to their files as they arrive (`WantsStream` reads handing back the octets past a body's end once it closes); a batch refused with NO or BAD is read one message after another with `fetch_raw`. No io-imap change: 0.7.1 carries `ImapMessageSort` and `ImapMessageFetchStreamBatch`.

**Wire.** `ImapWire` is the transport an `ImapSession` runs over (`Client`, or a scripted server in tests); `ImapSession` lost its JNI lifetimes (`ImapSession<'a>`), `MailSession::imap` with it.

**App.** `ImapStep` (`MailStep.of` for every account neither Google, Graph nor JMAP): the first step reads the inbox's floor (`MailEngine.floor`), else the most recent of the other stepped mailboxes', lists every mailbox from it (`MailEngine.syncSince`, junk and trash included for their checkpoints) and leaves it as the frontier, all mail when it is null; a later step runs `WidenStep`. Then the bodies the store lacks between the new frontier and the old (`MailStore.bodyRows`) download per mailbox through `MailEngine.download`, rows marked `always` since the frontier has not moved yet, junk, trash and mailboxes kept whole left out. `WidenStep` stays for Graph and JMAP.

**Tests.** Rust (403 green): imap_tests.rs over a scripted server cutting its replies into 7-byte reads (a sorted floor ignores an imported newest UID, a sorted floor widens its window and reads no header twice, a round after it reuses the headers, no-`SORT` odd date, a search cached until UIDNEXT or HIGHESTMODSEQ moves, bodies streamed in one fetch to files with a gone UID left out, a refused batch read one by one, batch cutting), listing.rs (`take_received`); the inline imap.rs tests moved there. Java: `ImapStepTest` (a first step from the inbox floor with bodies, junk listed but not downloaded; a small account held whole; a later step reading the 50 bodies between the frontiers).

**Left open.** The live check on the Fastmail test account (the password store's gpg pinentry timed out, so none ran) and the device test. A first step whose inbox holds fewer than 50 messages costs one floor per other mailbox. The `SENTSINCE` window of a floor with no ceiling is anchored on today.
