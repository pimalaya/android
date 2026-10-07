---
cairn: change
id: reply-and-row-marks
status: landed
created: 2026-10-07
---

# Reply, forward, and a quieter mail row

## Why

The second pass on the design, looked at on the device. The mail row carried four trailing things (star button, attachment chip, dot on the far side, replied mark) where the design reads as a few glyphs on one edge. The reader could not answer a message, the one thing a reader is for. The contacts list selected with a checkbox while mail used the avatar. And the item pages put each section's label outside its card, where the design keeps a section whole inside one.

## What

- Mail row: the subject's line ends, from the edge inwards, with the unread dot, a star shown only when the message is important (a mark, not a button), and a paperclip when it carries an attachment; a hairline parts the rows of a card.
- Reader: the mailbox and account as a subtitle under the subject; the meta card on single lines, scrolling sideways; Reply (to all) and Forward under the message.
- Composer: recipient chips in a faint tone that shows on the card; a reply carries `In-Reply-To` and `References` from the stored `Message-ID`, and marks its parent answered where the backend keeps that marker.
- Contacts list: selection by the avatar like mail, no checkbox; each row names its addressbook and account.
- Item pages: each section, label and rows together, is one card.
