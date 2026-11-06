---
cairn: tasks
change: imap-link-id-per-account
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [x] rust/src/mail/imap.rs: `link_id` (`<UID>:<UIDVALIDITY>:<mailbox>`) names every listed member, the handle staying the UID; rust/src/mail/listing.rs `Named` doc
- [x] pimdir/PimdirStorage.java: a message filed under the link id its listing named (`applyWrites(writes, listed)`), no longer under its handle; pimdir/Stager.java hands the driver's `listedLinks`
- [x] mail/MailEngine.java: remembers each page's and step's names before `nameByMessageId`; `pushRemove` falls back on the source's bound link id
- [x] mail/MailReader.java: the reader fetches by the handle
- [x] Audited, already on the handle: pushes, body fetches, MailStager, `nameByMessageId`, bound_elsewhere.sql (comment trimmed), Deleted items, notifications, search, outbox
- [x] Tests: rust `two_mailboxes_reusing_a_uid_name_two_messages`; ImapLinkIdTest (two mailboxes reusing UID 42 keep their own attachments, the reader fetches by UID, a sent copy lands under its arrival's link id, a move, a copy and a marker address the UID); the first and the sent copy failing on the code before
- [x] CHANGELOG.md
- [ ] Device check by the owner: an IMAP account removed and added again, then the cases above
- [ ] Fold the delta into spec/mail.md and spec/offline-store.md, log, archive
- [ ] Two IMAP accounts sharing a mailbox name, UIDVALIDITY and UID share a link id, so a `seq` and reference endpoints (pimdir does not scope them by account); attachments stay apart, listed per account. Decide whether the account joins the key
