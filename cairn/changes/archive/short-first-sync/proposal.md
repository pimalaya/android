---
cairn: change
id: short-first-sync
status: landed
created: 2026-10-07
---

# A short first sync: the newest chunk of each mailbox, one domain per tab

## Why

On the owner's Microsoft 365 test account (about 1 GB, 3,748 messages in 12 mailboxes), the modal dialog after sign-in stayed up about 96 s: contacts 8 s, every mailbox's whole metadata 65 s, calendars 23 s. Graph's network share of the mail was about 20 s; the rest was the bridge and the store. Mailboxes were synced in the server's order, the inbox sixth. The second pass took 2 s: deltas are fine, the first pass is what waits.

## What

1. **The modal stays, but short.** A domain's first sync runs the first time its tab is reached. The connection flow lands on mail, whose dialog syncs mail alone; contacts and calendars sync, each behind its dialog, the first time their tab is opened. Later passes (pull, drawer, background) are unchanged.
2. **Chunks, not whole mailboxes.** A mailbox's first pass lists its 50 newest messages: the scope's floor is the oldest `Date` among them (the source's 50 newest: the last 50 UIDs, Graph's `$top` by `sentDateTime`, Gmail's list, JMAP's `receivedAt`), unbounded when it holds fewer. A chunk is a number of messages, never a span of time; only the floor is kept, and it is the coverage io-pimdir already holds. The first dialog waits for these chunks alone, inbox first.
3. **Scrolling widens.** The merged list reaches down to the most recent floor among the mailboxes it shows; past it, a row loads the next 50 of every shown mailbox holding that floor (a k-way merge by floor), or says older mail needs the network.
4. **A background fill.** After the first dialog, while the app is in the foreground on an unmetered network, every mailbox widens 500 messages at a time toward the account's bound, the inbox and the sent mail first, without a dialog. It stops on a metered network, in the background, on another sync or on an error, and resumes from the floors it reached.
5. **Graph widens by a round.** A Graph delta link made under a filter is bound to it, so a widening relists the wider scope; listing the band by plain `/messages` would leave the delta blind to it, which needs a pimdir change (a checkpoint per band) and is not hacked around.
6. **Inbox first** on every pass: inbox, sent, drafts, the rest by name, junk and trash last, by the role each source states, now stored as pimdir's collection role.
7. **Measure the bridge.** Each mail page logs its network, JSON, engine and store time. The store dominates; Rust cannot write Android's SQLite, so the store's writes are cut instead.
8. **io-pimdir f9b13f8**: an undated message's empty `Date` is stored as `NULL`.

## Out of scope

- A per-band checkpoint for Graph (pimdir spec).
- The JSON seam itself: under 5% of a page's time.
