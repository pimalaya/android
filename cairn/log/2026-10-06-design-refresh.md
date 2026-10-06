---
cairn: log
change: design-refresh
landed: 2026-10-06
---

# The three domains take the unified design's layout

The unified app design settled how the lists, the reader, the composer and the navigation look. Its layout and shapes land; its fixed dark palette does not, every colour still coming from the device theme (DeviceDefault, light and dark), the cards, pills and bottom bar on `@color/surface` and the bars on the page tone.

Capabilities moved: offline-store (added: the bottom bar switches domains, a list opens on a large title, a list groups its rows into cards; modified: a list bar carries its actions inline; removed: the drawer switches domains), mail (modified: a row's trailing marks; added: the mail list narrows what it shows), calendar (added: the agenda offers the coming days).

**Navigation.** A bottom bar (`bottom_nav`) switches domains, mail carrying an unread badge counted over the listed page; it takes the bottom system inset, and shows on the three lists only. The drawer drops its domain rows for the mailboxes, each narrowing the mail list to that name across accounts (`MailList.showMailbox`), which is view-only and leaves syncing to the filter.

**Lists.** `ListHeader` puts a large title, a count and the domain's filters (search, chips, the day strip) above each list, hands the bar the title once it scrolls away and folds the new extended add button (`fab_extended`) while scrolling down. `CardSections` flattens rows into sections and rounds each card through its rows' backgrounds (`row_card_*`), since a ListView has no container to round. Mail groups by day with a dot, the sender, the time, the subject, a star toggling the important marker in place (`MessageView.stageFlag`, now shared with the reader) and an attachment chip; contacts group by letter, the sticky letter giving way to section headers that select their section, and their search moves from the bar into the header; the agenda groups by day, leads each row with its start, and offers fourteen days to jump to.

**Reader and composer.** The reader leads with the subject and its star, a mailbox tag and a sender card, unread and delete moving into a bar pill (`message_actions`). The composer is one card of labelled rows and sends from an accent bar button (`bar_send`) rather than the FAB.

**Left out.** Reply, forward and composer attachments, which the design shows and the app does not support, and the design's snackbar undo.
