---
cairn: change
id: mail-download-window
status: active
created: 2026-10-10
---

# The mail download window: the list shows what is on the phone

## Why

Every stored header is listed, and the end of the list widens it further (`MailList.more`, `MainActivity.widenMail`), but only opened messages are readable offline (or every listed one under the *background* policy), and nothing tells which. The offline policy (`MailOffline.Policy`) asks a question nobody can answer at setup, and the badge (`store.unread(everything)`) counts every unread header the fill brought in, years of them. The list's floor is applied around the canonical statements (`MailStore.reaching`), which reads every stored row of the shown collections on each reload.

Part B of docs/mail-window-files-references-plan.md, step 2 (agreed 2026-10-10). Text extraction (a pimdir `Text` level), an onboarding question and a post-sync sheet were considered and dropped there; this change does not reopen them.

## What

### The window

- Each mail account keeps a **window date** (`MailWindow`, app state beside the account as `MailScope` is, not in pimdir). Messages dated on or after it are downloaded whole (plain pimdir `Full`, the server's bytes, attachments included); the list shows exactly them.
- **Initial value**, at the end of the account's first mail sync, nothing asked: the inbox's first-chunk floor (`MailStore.coverage(inbox).since`); for Gmail (listed account-wide) or an account with no inbox, the most recent floor among its mailboxes (what `MailEngine.accountFloor` computes, read here from `MailStore.edges()`); all mail when every mailbox fitted in its first chunk; first of the current month for an empty account.
- **Moves back only by the user**: the footer, its picker, the settings row. Never forward in this change.
- **Bounded by the period** (`MailScope`): never older than its floor. A date picked below it widens the period to the smallest choice covering it (or all) and says so. Narrowing the period raises a window left below it. *All my mail* sets both to all mail, which is what the *whole mailbox* policy was.
- Headers keep syncing beyond the window, so search covers every stored header; a result beyond the window is dimmed, *Not downloaded*, and downloads when opened.

### The list floor

**list floor = max(window of every shown account, coverage floor of every shown collection)**. A mailbox kept whole brings no window (its floor is its coverage floor). The outbox stays on top. The count, day headers, select-all and chips follow it; search ignores it. The badge counts unread mail within each account's window, every mailbox the filter shows.

The floor moves into the store: pimdir gains a `:since` parameter on `list_mail_page_filtered`, `count_mail`, `count_mail_by_day` and `count_unread` (applied on the sort-key index), and a new `sum_mail` (count, summed `size`, count of unknown sizes over collections, chips and a `[since, until)` range). Another agent is landing it in pimdir and io-pimdir now. The bridge needs no code for it: `Native.pimdirSql` hands Java every statement of `io_pimdir::sql::all()` (rust/src/ffi/pimdir.rs), so the app takes it by bumping its io-pimdir pin. `MailStore` binds the new parameters; its `reaching` SQL wrapper goes.

### The footer

Replaces the *more* row. *Load since 1 September*, an info line (*1,240 messages · about 85 MB*, from `sum_mail` between the date and the floor), and *Choose a date* (a date picker with *All my mail*, the info line previewed). The date is the first of the month of the newest stored message below the floor, so empty months are skipped; with none stored but headers still to list, the latest first of a month before the floor. A tap moves every shown account's window to `min(window, date)`, lists the band down to the date through the engine's band round (`MailEngine.list`, made reachable), replans the bodies and reloads. It works offline (the band and the bodies wait). It follows the chips, hides under search, and stands in for the empty state of an empty window.

### What downloads

Per window, not per policy: every enabled account's rows dated on or after its window in every mailbox but junk and trash, plus every row of a mailbox kept whole; newest first, shown mailboxes first, hidden ones too; pending creates skipped. On a metered network bodies up to 256 KB only, a row of unknown size counting as large unless its attachment mark is 0; the rest waits for Wi-Fi, its row saying so. The per-account metered switch goes. The window's bodies come before the header fill's next chunk. Opening a message still downloads it whatever the window.

### Settings and the reader

- Account settings: the *Download messages* row and *Also on mobile data* switch go; a row *Mail on this phone since 1 September* opens the picker, offering only earlier dates. The period stays (no longer dimmed: no *whole* policy holds it). *Download this mailbox* stays on the filter page.
- Rows without a body are dimmed; *Downloads on Wi-Fi* while metered holds them; *Not downloaded* below the window or in junk/trash.
- Opening an undownloaded message offline says it is not on the phone yet (it will download once online when in the window; open it again online otherwise), never a blank page.

### Notifications

`BackgroundJob` notifies only messages dated on or after the account's window and after the newest one it already notified, so an in-app fill landing between two runs never notifies old unread mail.

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
- **Offline footer tap**: the band is not retried on reconnect; the background fill lists it, and the coverage part of the floor keeps the list continuous meanwhile.
- **Picker**: any day (midnight UTC, as `MailScope.since` writes dates), not snapped to a month; the settings picker offers dates before the window only (moving it later is a later step).
- **Notification cutoff state**: the newest notified `sort_key` per account, kept by `BackgroundCheck` and forgotten with the account.
- **`whole` stays in `MailOffline`**, narrowed to it plus the migration's read of the old keys, rather than moving its preferences into `MailWindow`.
- **Offline open screen**: two wordings, since *it will download when you are back online* is only true within the window.
- **Date in the button**: the year shown only when it is not the current year.

## Active changes this touches

Their folders are not edited; their folds carry these adjustments.

- **account-settings-page**: "An account's settings apply as they are changed" lists *the mail sync period and download policy*: becomes *the mail sync period and the window*. Its proposal's Mail card loses *Download messages* and *Also on mobile data* and gains the window row. Scenario "Picking a sync period" still holds; narrowing now also raises the window (this delta's "An account bounds its mail"). This change lands after it.
- **background-check**: "New mail notifies" keeps its wording; this delta's "A notification reaches no lower than the window" narrows it. Its scenario "Widening the bound" still holds, and now also holds when a fill lands between two runs, which the inbox diff alone did not guarantee.
- **onboarding-options-ask-on-switch**: its MODIFIED "New mail notifies" (off until turned on) is unaffected; the cutoff applies on top. Its setups gain nothing from this change: no window question at onboarding.

## Out of scope

- Plan B steps 3 and 4: releasing bodies back to `Meta` in pimdir, then moving the window later from settings to free them. Until then the window only grows; bodies opened beyond it stay.
- A3 (Graph message size, rust/src/client/graph_mail.rs): in parallel, by another agent; until it lands Graph sizes are unknown and the info line says so.
- Part C (files, references), the preview snippet.
