---
cairn: tasks
change: full-mail-index
---

## 0. Benchmark

Carried by pimdir `scoped-mail-sync` task 0, which measured Graph only (2026-10-07). Nothing below was measured here; the page sizes are that plan's defaults.

- [ ] Seed a test IMAP account with ~100k synthetic messages (local Dovecot or pimalaya.org Workspace)
- [ ] Time `UID FETCH 1:* (UID FLAGS INTERNALDATE ENVELOPE)` with and without `BODYSTRUCTURE`, and with `BODY.PEEK[HEADER.FIELDS (CONTENT-TYPE)]`
- [ ] Time SQLite inserts over JNI at 1k, 2k and 5k per transaction
- [x] Record results in docs/performance.md; settle attachment strategy, chunk size, Gmail default (settled by the joint plan: `Content-Type` mark, its page sizes, no Gmail default bound; recorded as unmeasured)

## 1. Whole-mailbox enumeration (rust/src/client)

- [x] IMAP: drop `PER_MAILBOX` windowing; enumerate the whole mailbox newest first in UID chunks, carrying a resume cursor (500 UIDs a page, cursor the lowest UID under its UIDVALIDITY)
- [x] IMAP: bulk fetch returns the summary (envelope, flags, internal date, attachment mark) in the enumerate round (header fields and `RFC822.SIZE`, no `ENVELOPE`, no `INTERNALDATE`, no `BODYSTRUCTURE`)
- [x] JMAP: page `Email/query` sorted by `receivedAt` to the end, `Email/get` with summary properties only; `Email/changes` deltas
- [x] Graph: filtered message delta, `Prefer: odata.maxpagesize=1000` on every link, summary `$select`, delta link at the end (joint plan, in place of `$top` listings)
- [x] Gmail: page `messages.list` to the end, metadata reads within quota (100 ids a page, one paced read each)
- [x] Honour the account's bound (all, or last N months) on every backend

## 2. Store and engine

- [x] Check io-pimdir: can an enumerate round file items with summary and sort key directly (yes, upstream: io-pimdir 35a1c3f names every member at enumeration)
- [x] Remove the probe-then-upgrade step from `MailEngine.sync`
- [x] Commit each chunk in one transaction; resume an interrupted first pass from its cursor
- [x] Complete round retires against the whole mailbox (or the bound), not a window
- [x] Correct the attachment mark when a message is opened and its body parsed

## 3. Lazy list (android/app)

- [x] io-pimdir/JNI: count, page (keyset or offset on `sort_key`), per-day counts, unread count, all under the filter's accounts and mailboxes and the chips (the canonical statements over JNI, run on Android's SQLite)
- [x] Rewrite `MailList` adapter: size from the count, LRU page cache around the scroll position, placeholder row while a page loads
- [x] Day sections from per-day counts, no row loaded to place a header
- [x] Search through the store (pimdir `search_mail`, `LIKE`), debounced
- [x] Outbox rows stay on top, outside the paged query
- [x] Selection and select-all keyed by store id, select-all as a query not a walk over rows in memory

## 4. Settings

- [x] Account option: sync all mail, or the last N months

## 5. Land

- [x] `:app:assembleDebug` and `:app:testDebugUnitTest` green, `cargo fmt`
- [x] Fold delta into cairn/spec/mail.md, rewrite its preamble (window wording, probes)
- [x] Log entry, CHANGELOG.md entry, archive the change
