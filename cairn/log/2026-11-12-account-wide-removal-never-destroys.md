---
cairn: log
change: account-wide-removal-never-destroys
date: 2026-11-12
---

# A removal on Gmail or JMAP never destroys a message held elsewhere

Found by the review of the delivered-create change (2026-11-11), confirmed by test on the released code: a data loss in 0.2.0, on Gmail and JMAP alike.

- The loss: a message deleted on the phone stages a move to Trash and a pending create there. When another client had already trashed it and the trash is listed before the inbox pushes in the same pass, the create lands; the inbox listing does not state the message gone, its tombstone has no destination left (io-pimdir's destination_for_link names no landed create), so it derives a plain Remove, and MailEngine.pushRemove destroyed it: `messages.delete` on Gmail, bypassing Trash, and JMAP's `Email/set` destroy. On an account listed account-wide one id names the message in every mailbox, so a removal from one mailbox destroyed it everywhere.
- The fix: on an account-wide account, a Remove with no destination takes the message out of that one mailbox (Gmail removes the label, `untrash` when leaving Trash; JMAP removes the mailbox from `mailboxIds`) while the account binds the same server handle live in another of its mailboxes, Gmail's All Mail aside (live_elsewhere.sql, by handle: a Message-ID names nothing, two messages may share it and one message may sit under two keys). A message held nowhere else is destroyed as before, which a delete for good from the trash and a move whose trash copy was deleted for good before the push need. New `Native.detachMessage`, `MailClient.detach`, `session.detach`.
- Tests failing first (MailMoveTest): `aGmailDeleteLandedInTheTrashFirstIsNeverDestroyed` (before: `destroy INBOX 42`), `aGmailDeleteForGoodBesideAnotherMessageOfItsIdDestroys` and `aGmailRemovalOfAMessageHeldElsewhereUnderAnotherKeyLeaves` (the reviewer's probes against a first version matching by Message-ID); `aGmailDeleteForGoodFromTheTrashDestroys` guards the intended destroy.
- A detach removing a Gmail message's last label leaves it in All Mail, which the app does not show: still on the web and in search, accepted over a destroy.
- Checked: on e54d705 alone by the agent; merged here over the contacts and calendar fixes of the day and run again.

Capabilities moved: mail (A message is deleted into the account's trash, modified: the account-wide removal).
