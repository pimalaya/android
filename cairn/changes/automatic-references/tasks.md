---
cairn: tasks
change: automatic-references
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [x] rust/Cargo.toml io-pimdir f6ca07f, Cargo.lock; `invitation` on the summary wire and in the Graph and JMAP summaries
- [x] rust/src/mail.rs: stand-in parts are those disposed as attachments, in document order; test
- [x] `PimdirDb`: `collections` rebuild, invitation backfill and the four rules once, roles of existing attachments collections (`FileStore.reconcileRoles`)
- [x] `PimdirSummary.write` runs the kind's rule; `link`, `linkAll`
- [x] `MailStore.restateFromBody` in place of `markAttachment`; `MailEngine.download`, `MessageView.fetch`
- [x] `FileStore`: role, `partKey` null under a derived key, `attachmentsIn`, `delete_unbound_item`, `collection_holds_objects`
- [x] `ItemLinks`: `derived`, `describe_endpoint`, `search_*`, `add`
- [x] `MailStore.message` on `get_mail_row`; `MailStore.sum` takes `held`; `MailList`, `WindowPicker` (release count, strings fr)
- [x] `Locale.ROOT` lowercasing
- [x] Tests: `AutomaticReferencesTest` (both ends of each rule, stand-in parts, derived keys, an old store reconciled), `MailStoreTest` (held)
- [x] CHANGELOG
- [ ] Device test
- [ ] Fold the delta, log, archive (after attachments-as-files, files-tab, item-links)
