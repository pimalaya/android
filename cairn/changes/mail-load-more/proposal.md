---
cairn: change
id: mail-load-more
status: active
created: 2026-10-11
---

# Load more: mail steps instead of dates

## Why

The first Gmail device test of the download window (2026-10-10) took 94 s for a first sync, ran a 47 s silent header fill, and left a message impossible to open. The probes behind docs/mail-window-files-references-plan.md section B.13 found why the date-based window fails: a floor is the minimum `Date` of the newest messages, so one imported message drags a whole mailbox in; Gmail's binding limit is concurrency (batches of 10 drew no refusal in 2,250 reads, 25 drew 1.2 %, 50 drew 19 %), not its per-minute quota, which refills by the second; a body crosses JNI as base64 JSON, one round trip each. B.13 settles the revision: steps of about 50 messages instead of dates, Wi-Fi only, bodies as files. This change lands the generic part and Gmail; IMAP, Graph and JMAP follow on the same contract, each with its own step and body batching.

## What

### Generic

- **Wi-Fi only.** No mail pass listing, step, fill or body download on a metered network: the outbox still goes out, the strip says *Waiting for Wi-Fi*, and what was owed runs when Wi-Fi comes back while the app is open. Opening one message fetches on any network. A background run on a metered network sends the outbox and lists nothing. The 256 KB metered cap, its *Downloads on Wi-Fi* row label and the footer's metered hint go.
- **A step** (`MailStep`, one implementation per backend) brings about 50 messages of an account, whole, older than what it holds, never by offset, on the account's one session, small batches one after another. The first sync is one step. A view filtered to one mailbox of an account steps that mailbox.
- **The frontier** (`MailWindow`): per account, the `Date` of the oldest message its steps brought, a message dated more than a day before its reception left out (held, shown once the floor passes it). Steps only move it back; all mail once a step finds nothing older. The list floor stays the most recent of the shown accounts' frontiers and of the shown mailboxes' coverage floors, Gmail mailboxes bringing no coverage floor (their steps decide what they hold).
- **The footer** is one button, *Load more*, over *About 50 more per account* (*· Waits for Wi-Fi* off Wi-Fi, the button disabled). *Choose a date*, the next-date rule and the info line's counts go. The settings row keeps moving the frontier later to free bodies (B.9).
- **Arrivals** a delta lists new to the store download whatever their `Date`. Junk and trash are never stepped.
- **The bridge contract**: bodies cross JNI as files. `fetchMessageSources(handle, collection, ids, dir)` writes one file per message and answers `{id, path, size}` (Gmail adds `labels`, `received`), dispatching per backend: Gmail batches, the others read one message after another inside it until their batching lands. The engine files the file in the blob store and hands io-pimdir a persisted body (`PimdirFetchedBody::Persisted`). A step entry point (`mailStep`, Gmail's) answers the step's items named as a listing names them, with the mailboxes each belongs to and its file.

### Gmail

- A step walks the account's `messages.list` (no `labelIds`, `includeSpamTrash=false`, 500 a page) from the top, keeping the ids the store does not hold until 50: a set difference, never a `before:` on a date. A `format=minimal` pre-pass reads the sizes, then `format=raw` in batches of at most 10 messages and about 8 MB, one batch at a time, a message over about 5 MB alone. Labels come from `labelIds`: one read per message, linked into every label. A view filtered to one label steps that label.
- Quota constants in one place (`gmail::quota`): `messages.get` counted at the documented 20 units against 6,000 a minute, the measured 5 units against 15,000 noted beside them. A per-minute refusal backs off 2 s, then a second more each time up to 5 s, never to the next wall-clock minute and never draining the account's pacing; a concurrency refusal is put back into the next batch after 1 to 2 s with jitter; a daily quota stops the read. Batches of 10 (were 25).
- The Gmail background header fill, the first chunk, the floor walk and the batch-past-the-floor stop go. History replay stays; an expired history relists each label's ids received since the oldest reception the account holds, named by the unread and starred labels' ids, the store keeping what it holds and reading only what arrived ahead of it or is newly filed: never one read per held message.

## Active changes this touches

Their folders are not edited; their folds carry these adjustments.

- **mail-download-window** (not folded): its ADDED "A mail account keeps a download window" loses its initial value (the first step's frontier replaces it) and its moves back from the footer, picker and settings (the settings row moves later only); "The mail list ends on a footer moving the window back" is replaced by this delta's "The mail list ends on Load more"; "A row not on the phone is dimmed" loses *Downloads on Wi-Fi*; its MODIFIED "Older mail fills in behind", "An account's mailboxes sync side by side" and "Bodies download behind the list" take this delta's wording; "A mailbox can be downloaded whole" downloads on Wi-Fi, no size cap; "An account's settings show its window" offers days after the window only. Its fold takes this delta's wording wherever both touch a requirement.
- **gmail-first-sync-stall** (folded, device test pending): the throttling, pacing, stored-whole and fill requirements it wrote are modified here directly.

## Out of scope

- IMAP, Graph and JMAP steps and body batching (their agents, on this contract; until then `WidenStep` steps them from the store and their bodies are read one by one inside `fetchMessageSources`).
- Listing junk and trash on demand when opened (B.13 rule 7's second half).
- A size on the footer's line: no backend knows a step's size before it runs.
