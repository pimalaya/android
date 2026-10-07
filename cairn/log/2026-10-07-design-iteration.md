---
cairn: log
change: design-iteration
landed: 2026-10-07
---

# Second pass on the unified design

A look on the device after the first pass. Most of it closes the gap with the design; the drawer departs from it on purpose. The design's drawer is one account's mailboxes, but this app is one merged view, and a drawer opening one mailbox would compete with the filter that narrows the merged list. So the drawer lists accounts, and the mail list stays one page.

Capabilities moved: mail (modified: a row's trailing marks, the mail list narrows what it shows; added: the mail list selects), calendar (removed: the agenda offers the coming days; added: the agenda offers the week), offline-store (modified: the bottom bar switches domains, a list opens on a large title; added: an item's page groups its sections into cards).

**Drawer.** The app's name over one card per account (`MainActivity.accountCard`): a disc, the address, the domains it covers, a pill saying when it last synced, and a corner check while the filter shows it and, for an account holding addressbooks, one of them is on. `SyncStamps` records the time per account when its mail, calendar or contacts pass comes through clean. Mailbox rows and `MailList.showMailbox` are gone.

**Mail.** Taps had never reached the list: an `ImageButton` makes itself focusable in its constructor, whatever the XML says, so the star swallowed the row's click. `blocksDescendants` on the row fixes it. A long press starts a selection, with the disc turning into a check and the bar carrying read, star and delete over all of it. The reader's write paths now take a list (`MessageView.stageFlag`, `stageDelete`). The star sits in a frame centred on the subject's fixed-height line, filled yellow (`@color/star`, the one fixed hue) when on.

**Chrome.** The extended add button folds by animating its width to a square, where before it only dropped its label. It carries a pencil on mail and a person-plus on contacts. The bottom bar's indicator is the text colour at low alpha (`@color/nav_indicator`). The selection close and select-all buttons are shared by contacts and mail (`selection_*`).

**Pages.** `Sections` draws each section as a label over one rounded card, which the contact editor and the entry page both use. The agenda's day strip gives way to this week in one card under the month and year (`ListHeader.week`); days already gone are dimmed, since the agenda starts at today.
