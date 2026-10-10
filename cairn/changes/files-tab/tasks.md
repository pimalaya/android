---
cairn: tasks
change: files-tab
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [x] `FileStore`: `StoredFile` with its collection (replacing `Attachment`); `isAttachments`, `attachmentCollections`, `files` (paged `list_files_page_asc`), `origin` (`references_to`), `importFile`, `delete`, `renameFolder`, `deleteFolder`, `label`
- [x] `MailStore.message(collection, linkId)`: one stored message, for *Show the message*
- [x] `MessageView`: `bytesOf` shared with the Files tab
- [x] `FilesList` and panel_files.xml: sections, chips, search, meta, empty state, file and folder actions
- [x] `OpenedFiles`: sharing
- [x] `MergedFilter` keyed by a string; `FilterPage.openFiles`
- [x] `MainActivity`, `Domains`, activity_main.xml: the fourth tab, its screen, filter, import and export results
- [x] Strings in values/ and values-fr/
- [x] Tests: `FileStoreTest` for folders, imports, deletes and origins
- [x] CHANGELOG [Unreleased]
- [x] Moved by automatic-references: the attachments read by `list_attachments_by_account`, a folder's copy deleted by `delete_unbound_item`, `collection_holds_objects` sparing the recount, the attachments collection told by its role
- [ ] Device test
- [ ] Fold the delta into spec/mail.md, log, archive
