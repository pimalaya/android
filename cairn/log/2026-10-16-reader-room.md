---
cairn: log
change: reader-room
landed: 2026-10-16
---

# The reader leaves the page to the message

Capabilities moved: mail (ADDED The reader leaves the page to the message, A message is moved or copied; MODIFIED The reader replies and forwards, now from the bar).

The reader was too narrow for the message: a four-line subject with a star, a sender card scrolling sideways whole, a Linked badge under it and two large reply and forward buttons at the bottom. The subject now takes two lines, the star goes, and the sender card folds by default to the sender and how long ago, a tap unfolding the recipients, the exact date and the Linked badge; only its text scrolls sideways. Every action is an icon in the bar: delete, move, copy, flags, forward, reply. Flags replaces mark read/unread and the star with a dialog ticking read, important and answered (where the backend keeps it).

Move and copy are new: they list the account's other mailboxes and stage a move or a copy in the store. The copy mutation already crossed the bridge (io-pimdir `Copy`, pushed as a server copy by MailEngine); only the Java `Mutation.Op.COPY` and `Stager.mutateCopy` were missing. HTML bodies drop the fixed table layout and the fixed initial scale, so a layout that cannot reflow is shrunk to fit rather than scrolled sideways, and both body kinds zoom on a pinch. Asked in-session with the design given, so no proposal was reviewed first. JVM tests gain a copy test in MailMoveTest.
