---
cairn: tasks
change: mail-named-by-message-id
---

# Tasks

- [ ] Map every place reading a mail link id as a server id, the proposal's list as a start
- [ ] Listing: each backend's member carries its `Message-ID` hint and its server id as handle; the key assigned by pimdir's table, through io-pimdir where it exposes the assignment, never transcribed into Java
- [ ] Every user of a mail link id moved to the handle or the identity
- [ ] Bodies linked by identity on every backend; mail.md's release rule replaced
- [ ] imap-link-id-per-account's encoding removed
- [ ] Tests, each failing on the old code: two IMAP mailboxes reusing a UID keep their own attachments; an IMAP move keeps the message's attachment records and a task's `source` link; a Graph move keeps them; a message without `Message-ID` lists, reads and syncs; two different messages sharing a `Message-ID` stay two; a Gmail sent copy lands on its pending create
- [ ] imap-link-id-per-account archived as superseded, with a log
- [ ] Device check: IMAP, Gmail, Graph and Fastmail accounts removed and added again
- [ ] Fold, log, CHANGELOG
