---
cairn: tasks
change: mail-named-by-message-id
---

# Tasks

- [x] Map every place reading a mail link id as a server id, the proposal's list as a start
- [x] Listing: each backend's member carries its `Message-ID` hint and its server id as handle; the key assigned by pimdir's table, through io-pimdir where it exposes the assignment, never transcribed into Java
- [x] Every user of a mail link id moved to the handle or the identity
- [x] Bodies linked by identity on every backend; mail.md's release rule replaced
- [x] imap-link-id-per-account's encoding removed
- [x] Tests, each failing on the old code (MessageIdNamingTest; rust `a_member_is_named_by_its_message_id_never_its_handle`, `a_member_is_named_by_its_header_not_its_uid`): two IMAP mailboxes reusing a UID keep their own attachments; an IMAP move keeps the message's attachment records and a task's `source` link; a Graph move keeps them; a message without `Message-ID` lists, reads and syncs; two different messages sharing a `Message-ID` stay two; a Gmail sent copy lands on its pending create
- [x] imap-link-id-per-account archived as superseded, with a log
- [ ] Device check: IMAP, Gmail, Graph and Fastmail accounts removed and added again
- [ ] Fold, log, CHANGELOG
- [x] pimdir's `source` rule run (`LINK_SOURCES_TO` after a mail write, `LINK_SOURCES_OF` after a task write): a task and the mail it came from now show each other in their links, which the app never recorded
- [ ] Follow-up: a UIDVALIDITY change mints every message a `dup:` key and parks the round on the absent old bindings; pimdir's rekey (SYNC §8) is the answer (proposal, Open)
- [ ] Follow-up: a retained row revived under a reappearing `Message-ID` without the §9 agreement check (io-pimdir's load cannot see retained rows)
- [ ] Follow-up: a move's target withdrawn on delivery rather than landed (SYNC §5); links survive by the `Message-ID`
