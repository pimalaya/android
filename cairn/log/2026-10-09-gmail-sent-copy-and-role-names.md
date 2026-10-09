---
cairn: log
change: gmail-sent-copy-and-role-names
landed: 2026-10-09
---

# Gmail's sent copy lands once, role mailboxes go by their role, the sync dialog names the account

Capabilities moved: mail (added: a mailbox with a role is shown under the role's name; modified: a sent message is in Sent at once), offline-store (modified: a sync says what it is working on).

**Sent copy.** Gmail's `messages.send` replaced the composed `Message-ID` (`<uuid@pimalaya.org>`) with one of its own (`<CACE…@mail.gmail.com>`), seen on the device on 2026-10-09: the staged copy never matched Gmail's, so Sent listed both, the staged one pending for ever. The Gmail submission now reads its copy back by the id `messages.send` answers (one metadata read) and returns that `Message-ID` over `submitMessage`; the drain remembers it as an alias of the staged copy (app preferences, `mail-sent-alias`), and `MailEngine.nameByMessageId` lands an arrival carrying it on the staged create. A failed read leaves the copy unmatched rather than failing a send that went. Graph is unchanged and still a live check.

**Role names.** A mailbox whose source states a chip's role is shown under the chip's name (`MailStore.mailboxLabel`): Gmail's system labels are named by their ids (`SENT`, `SPAM`), IMAP's inbox `INBOX`. `StoredMessage.mailbox` stays the server's name, which the reader addresses a message by.

**Sync dialog.** The title is `account · domain` once a pass reaches an account: mail and calendar at each account's fetch, contacts through a new `SyncRunner.Observer.account` hook.

**Gmail quota.** The Cloud console of project 991810147220 states 6,000 units per minute per user, not the 15,000 Google documents: the per-minute budget is now 4,800 (google-sync, still active).
