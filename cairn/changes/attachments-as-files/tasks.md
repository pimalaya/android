---
cairn: tasks
change: attachments-as-files
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [x] rust/Cargo.toml: io-pimdir patch rev fc73663809bb052dcc9fcaed84ee7f153bd9e6ad, Cargo.lock refreshed
- [x] rust/src/summary.rs: the `file` summary on the wire (`PimdirSummary::File`)
- [x] rust/src/mail.rs: attachments carry their section; `part(raw, section)`; media type empty when unstated; tests
- [x] rust/src/ffi/mail.rs: `Native.messagePart`; client `PimalayaClient.messagePart`, `MessageBody.Attachment.part`
- [x] `FileStore`: stand-in collection per account, `recordAttachments`, `attachments` (`list_attachments`), `folders`, `createFolder`, `save`, `saved`, `forget`
- [x] `MailEngine.download` and `MessageView.load` record the attachments where the mark is restated; `MailStore.forget` drops the stand-ins
- [x] `MessageView`: badges from the store, Open and Save to folder, folder picker with New folder
- [x] `OpenedFiles` provider and its manifest entry
- [x] Strings in values/ and values-fr/
- [x] Tests: `PimdirDbTest` (an older store gains the file table and trigger), `FileStoreTest`
- [x] CHANGELOG [Unreleased]
- [ ] Device test: open and save an attachment, offline and online, a message whose body was released
- [ ] Fold the delta into spec/mail.md, log, archive
