---
cairn: change
id: mail-download-window
status: active
created: 2026-10-10
---

# The mail download window: the list shows what is on the phone

## Why

Every stored header is listed, and the end of the list widens it further (`MailList.more`, `MainActivity.widenMail`), but only opened messages are readable offline (or every listed one under the *background* policy), and nothing tells which. The offline policy (`MailOffline.Policy`) asks a question nobody can answer at setup, and the badge (`store.unread(everything)`) counts every unread header the fill brought in, years of them. The list's floor is applied around the canonical statements (`MailStore.reaching`), which reads every stored row of the shown collections on each reload.

Part B of docs/mail-window-files-references-plan.md, steps 2 and 4 (agreed 2026-10-10; step 4 added on review, once pimdir's release landed). Text extraction (a pimdir `Text` level), an onboarding question and a post-sync sheet were considered and dropped there; this change does not reopen them.

## What

### The window

- Each mail account keeps a **window date** (`MailWindow`, app state beside the account as `MailScope` is, not in pimdir). Messages dated on or after it are downloaded whole (plain pimdir `Full`, the server's bytes, attachments included); the list shows exactly them.
- **Initial value**, at the end of the account's first mail sync, nothing asked: the inbox's first-chunk floor (`MailStore.coverage(inbox).since`); for Gmail (listed account-wide) or an account with no inbox, the most recent floor among its mailboxes (what `MailEngine.accountFloor` computes, read here from `MailStore.edges()`); all mail when every mailbox fitted in its first chunk; first of the current month for an empty account.
- **Moved only by the user**: back from the footer, its picker or the settings row; later from the settings row alone, which frees the bodies below (plan B step 4, below).
- **Bounded by the period** (`MailScope`): never older than its floor. A date picked below it widens the period to the smallest choice covering it (or all) and says so. Narrowing the period raises a window left below it. *All my mail* sets both to all mail, which is what the *whole mailbox* policy was.
- Headers keep syncing beyond the window, so search covers every stored header; a result beyond the window is dimmed, *Not downloaded*, and downloads when opened.

### The list floor

**list floor = max(window of every shown account, coverage floor of every shown collection)**. A mailbox kept whole brings no window (its floor is its coverage floor). The outbox stays on top. The count, day headers, select-all and chips follow it; search ignores it. The badge counts unread mail within each account's window, every mailbox the filter shows.

The floor moves into the store: pimdir gains a `:since` parameter on `list_mail_page_filtered`, `count_mail`, `count_mail_by_day` and `count_unread` (applied on the sort-key index), and a new `sum_mail` (count, summed `size`, count of unknown sizes over collections, chips and a `[since, until)` range). Another agent is landing it in pimdir and io-pimdir now. The bridge needs no code for it: `Native.pimdirSql` hands Java every statement of `io_pimdir::sql::all()` (rust/src/ffi/pimdir.rs), so the app takes it by bumping its io-pimdir pin. `MailStore` binds the new parameters; its `reaching` SQL wrapper goes.

### The footer

Replaces the *more* row. *Load since 1 September*, an info line (*1,240 messages · about 85 MB*, from `sum_mail` between the date and the floor), and *Choose a date* (a date picker with *All my mail*, the info line previewed). The date is the first of the month of the newest stored message below the floor, so empty months are skipped; with none stored but headers still to list, the latest first of a month before the floor. A tap moves every shown account's window to `min(window, date)`, reloads at once (the stored rows show dimmed), lists the band down to the date a chunk at a time (`MailEngine.widen` with a stop date), replans the bodies and reloads. It works offline (the band and the bodies wait). It follows the chips, hides under search, and stands in for the empty state of an empty window.

### What downloads

Per window, not per policy: every enabled account's rows dated on or after its window in every mailbox but junk and trash, plus every row of a mailbox kept whole; newest first, shown mailboxes first, hidden ones too; pending creates skipped. On a metered network bodies up to 256 KB only, a row of unknown size counting as large unless its attachment mark is 0; the rest waits for Wi-Fi, its row saying so. The per-account metered switch goes. The window's bodies come before the header fill's next chunk. Opening a message still downloads it whatever the window.

### Settings and the reader

- Account settings: the *Download messages* row and *Also on mobile data* switch go; a row *Mail on this phone since 1 September* opens the picker over the filter's mailboxes of the account, any day up to today: earlier moves back, later asks then frees. The period stays (no longer dimmed: no *whole* policy holds it). *Download this mailbox* stays on the filter page.
- Rows without a body are dimmed; *Downloads on Wi-Fi* while metered holds them; *Not downloaded* below the window or in junk/trash.
- Opening an undownloaded message offline says it is not on the phone yet (it will download once online when in the window; open it again online otherwise), never a blank page.

### Notifications

`BackgroundJob` notifies only messages dated on or after the account's window and after the newest one it already notified (that mark kept no later than the run, and seeded from the newest unread inbox message when none is held), so an in-app fill landing between two runs, or an upgrade, never notifies old unread mail.


### Moving the window later (plan B step 4)

The settings picker also offers days after the window. Confirmed ("Messages before 1 September leave this phone. Their headers stay..."), the window moves there and `MailStore.release` frees the bodies of the account's messages below it in every mailbox not kept whole: pimdir's owner statements `release_bases_before`, `release_before`, `recompute_refcounts` in one transaction, as io-pimdir's `PimdirStore::release_before` orders them, then the collector as `collectBefore` runs it. Items still needing their body (conflicts, pending creates, local edits, items a source does not bind) keep it. io-pimdir rev 1030bfc also folds an `item_reference` table, index and trigger into 0001_init.sql; `PimdirDb.reconcileDraftShape` already creates a missing table, index or trigger from the canonical schema on open, so an existing store gains them with no change.
### Migration

Testers only (unreleased). At start, once: an account on `BACKGROUND` or `WHOLE` takes its period's floor as window (its bodies are mostly held); one on `ON_OPEN` takes its inbox's first-chunk floor recomputed from the 50 newest stored inbox rows, so nothing downloads by surprise. The old `policy:` and `metered:` preferences are then dropped.

## Decisions settled here

The plan leaves these open or implicit; settled as follows.

- **Next date rule**: one rule covers both plan bullets: the first of the month of the newest stored row strictly below the floor (read as one row of `list_mail_page_filtered` keyed after the floor). Fallback when nothing is stored below but a shown mailbox still has headers to list (`MailScope.limits` on its coverage): the latest first of a month before the floor.
- **When the footer shows**: a stored row below the floor, or headers left to list below it; otherwise no button, and *Choose a date* alone while a shown account is bounded; nothing when the period is all mail and everything is listed.
- **Small first sync**: an account whose every mailbox fitted in its first 50 has no floor anywhere: its window is all mail (all of it is small); only an account with no mail at all takes the first of the month.
- **Window part of the floor**: only accounts showing at least one mailbox not kept whole contribute.
- **Badge and whole mailboxes**: a mailbox kept whole counts under its account's window for the badge, so an archive kept whole does not light it with old unread mail.
- **Junk and trash rows**: in the window but never downloaded (unless kept whole), so they are dimmed and say *Not downloaded*, as rows beyond the window do.
- **Metered total**: the info line's *on Wi-Fi* hint shows on a metered network when the summed size passes 10 MB, a constant beside the 256 KB cap; both are guesses to measure before release.
- **Unknown sizes in the info line**: *1,240 messages · 85 MB, size unknown for 40*; *1,240 messages · size unknown* when none is known; *about* only when all are known.
- **Bodies before the fill**: B.1's order is kept literally: a fill step lists nothing while the body step has bodies it may download now.
- **Band to a date**: listed a chunk of 500 at a time on the io thread (`MailEngine.widen` with a stop date), each chunk its own task as the fill's are, so an open waits one chunk at most; it stops when a sync starts or the network goes, the fill listing the rest, and the coverage part of the floor keeps the list continuous meanwhile.
- **Small inbox**: an inbox that fitted in its first chunk gives no floor; the window is then the most recent floor among the account's mailboxes.
- **Migration timing**: on the main thread in `onCreate`, before the first list read or body step, a few preference reads once migrated.
- **Picker and dates**: any day, not snapped to a month; every window date (the picker's, the footer's next date, the first of the month) is a day on the device's clock, stored as the UTC instant of its midnight there, as the day headers are. The bound's floor stays UTC (`MailScope.since`).
- **Notification cutoff state**: the newest notified `sort_key` per account, kept by `BackgroundCheck` no later than the run (a future-dated message cannot silence later ones), cleared when notifications are turned on and seeded by the next run from the newest unread inbox message, forgotten with the account.
- **`whole` stays in `MailOffline`**, narrowed to it plus the migration's read of the old keys, rather than moving its preferences into `MailWindow`.
- **Offline open screen**: two wordings, since *it will download when you are back online* is only true within the window.
- **Date in the button**: the year shown only when it is not the current year.

## Active changes this touches

Their folders are not edited; their folds carry these adjustments.

- **account-settings-page**: "An account's settings apply as they are changed" lists *the mail sync period and download policy*: becomes *the mail sync period and the window*. Its proposal's Mail card loses *Download messages* and *Also on mobile data* and gains the window row. Scenario "Picking a sync period" still holds; narrowing now also raises the window (this delta's "An account bounds its mail"). This change lands after it.
- **background-check**: "New mail notifies" keeps its wording; this delta's "A notification reaches no lower than the window" narrows it. Its scenario "Widening the bound" still holds, and now also holds when a fill lands between two runs, which the inbox diff alone did not guarantee.
- **onboarding-options-ask-on-switch**: its MODIFIED "New mail notifies" (off until turned on) is unaffected; the cutoff applies on top. Its setups gain nothing from this change: no window question at onboarding.

## Out of scope

- Releasing bodies on other occasions (a mailbox no longer kept whole, a bound narrowed keeps collecting whole items as today).
- A3 (Graph message size, rust/src/client/graph_mail.rs): in parallel, by another agent; until it lands Graph sizes are unknown and the info line says so.
- Part C (files, references), the preview snippet.
