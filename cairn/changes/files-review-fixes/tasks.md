---
cairn: tasks
change: files-review-fixes
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [x] rust: `mail::decoded` (raw slice, transfer encoding undone), `Native.messagePart` over files; tests (byte-exact Latin-1 and quoted-printable parts)
- [x] `PimdirHash.of(File)`, `PimdirBlobs.put(hash, File)`, `PimdirItems.storeObject(File)`, `blobFile`, `objectFile`; `MailStore.sourceFile`
- [x] `FileActions.Source`; `FileStore.saved`, `save`, `importFile` over files; `MessageView.fileOf`; `FilesList` import staged in the cache
- [x] `LinkedSection`: async reads and writes, no unlink for an attachment, picker guards; `MainActivity.openEvent` on io; reader's link count on io
- [x] `FileStore.recordAttachments` re-adds the reference; `createFolder` and `renameFolder` check the name
- [x] Export and import state across recreation; export reads first, deletes a failed document
- [x] `OpenedFiles`: extension after the last dot, `safe` names, `clear` on start and account removal; `FileActions.typeOf` falls back to the extension
- [x] `MailEngine.download` records each message's attachments on its own; "Already in" toast
- [x] Tests: `FileStoreTest` (byte-exact text part through the bridge, names taken, second save, attachment re-linked)
- [ ] Device test (a 50 MB attachment opened and saved, a rotation during import and export)
- [ ] Fold, log, archive with attachments-as-files, files-tab, item-links
