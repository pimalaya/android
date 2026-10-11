---
cairn: change
id: mail-load-more-imap
status: active
created: 2026-10-11
---

# Load more: the IMAP step

## Why

mail-load-more landed the generic step contract and Gmail; IMAP still steps from the store through `WidenStep`, its floor is the oldest `Date` among the 50 newest UIDs (one imported message dated years back pulls a whole mailbox in), and its bodies are read one `UID FETCH` per message. docs/mail-window-files-references-plan.md B.13 settles the IMAP step; this change lands it.

## What

- **The floor as an order statistic.** A mailbox's chunk floor is the `Date` of its 50th newest message by `Date`, never the minimum over a UID sample. Where the server advertises `SORT` (RFC 5256): `UID SORT (REVERSE DATE) UTF-8 SENTBEFORE <ceiling + 1 day>` bounded by `SENTSINCE`, the window widened from 90 days to a year to all mail until 50 dated messages lie below the ceiling, their header fields read in that order. Without `SORT`: the newest UIDs as before, a message whose `Date` is more than a day before its `INTERNALDATE` counting toward the 50 but not lowering the floor (the oldest of all when every one is such).
- **Cached searches.** The UIDs a `SEARCH` or `SORT` found are kept per (mailbox, UIDVALIDITY, scope) and the header fields a floor read are reused by the round after it, both dropped when the mailbox's UIDNEXT, HIGHESTMODSEQ or message count moves; the header fields are kept only where the server reports a HIGHESTMODSEQ, so a marker cannot go stale.
- **`ImapStep`.** The first step takes the inbox's floor F (the most recent of the others' when the inbox holds fewer than 50), lists every mailbox since F for its checkpoint, leaves F as the frontier, then downloads the bodies the step brought. A later step widens from the store as `WidenStep` does (the mailbox holding the most recent coverage floor by 50, until about 50 stored rows lie between it and the frontier, the frontier the 50th by `Date`, ties included), then downloads the bodies between the new frontier and the old, one mailbox after another. `WidenStep` stays for Graph and JMAP.
- **Bodies in streamed batches.** `fetchMessageSources` on an IMAP session reads the sizes (`UID FETCH <set> (UID RFC822.SIZE)`), then one streamed `UID FETCH <set> (UID BODY.PEEK[])` per batch of at most 50 messages and about 12 MB, a message over it alone, each body written straight to its file (io-imap 0.7.1's `ImapMessageFetchStreamBatch`, already published). A batch the server refuses falls back to one message after another. One connection, the account's session.

## Out of scope

- Graph and JMAP steps (their own changes).
- Junk and trash listed on demand, transport eviction, failed-body retry, frontier per mailbox (the generic work in the main tree).
- io-imap: nothing to add, 0.7.1 carries `ImapMessageSort` and `ImapMessageFetchStreamBatch`.
