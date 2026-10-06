---
cairn: change
id: design-refresh
status: landed
created: 2026-10-06
---

# Restyle the three domains after the unified design

## Why

The unified app design (a dark Material 3 mockup of mail, contacts and agenda) settles how the three merged lists, the reader, the composer and the navigation should look: large scrolling titles, rows grouped into rounded cards under section headers, an inline search, a bottom bar switching domains and an extended add button. The app still draws flat full-bleed lists under one bar, and switches domains from the drawer.

## What

Take the design's layout and shapes, and keep the device theme's colours (DeviceDefault, light and dark): no fixed palette is introduced.

- A bottom navigation bar switches between mail, contacts and calendars, mail carrying an unread badge. The drawer loses its domain rows and gains the mailboxes, each one narrowing the mail list to itself.
- Every list opens on a large title over a supporting line that the bar's own title replaces once scrolled away, and groups its rows into rounded cards under section headers: mail by day, contacts by letter (conflicts first, under their own header), the agenda by day.
- Mail gets an inline search and two filter chips (unread, attachments); each row shows an unread dot, the sender, the time, the subject, a star toggle and an attachment chip.
- Contacts search moves from the bar into the list header. Pressing a letter header selects that section, which the sticky letter did.
- The agenda header carries a strip of the coming days, pressing one scrolls to it.
- The list add button becomes an extended one, its label folding away while the list scrolls down.
- The reader leads with the subject and its star, the mailbox tag and a sender card; read state and delete move into the bar.
- The composer groups the sender, recipients and subject into one card of labelled rows, and sends from a bar button.

Out of scope: reply, forward and attachments in the composer, which the design shows and the app does not support yet; the design's snackbar undo.
