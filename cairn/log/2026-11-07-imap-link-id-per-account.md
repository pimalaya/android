---
cairn: log
change: imap-link-id-per-account
date: 2026-11-07
---

# An IMAP message's link id unique in its account, abandoned

Abandoned, superseded by mail-named-by-message-id. Capabilities moved: none, its delta never folded.

Built on 2026-11-06 (6b604e4): an IMAP link id `<UID>:<UIDVALIDITY>:<mailbox>`, so the inbox's message 42 and the archive's message 42 no longer shared one attachment record. On 2026-11-07 the owner found the root cause was the app naming a message by its server id at all, where pimdir names it by its `Message-ID` and keeps the server id as the handle (STORAGE §9), and chose to follow pimdir on every backend. mail-named-by-message-id removed this encoding.
