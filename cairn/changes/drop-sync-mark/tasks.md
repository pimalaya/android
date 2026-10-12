---
cairn: tasks
change: drop-sync-mark
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [x] Layouts item_message, item_contact, item_event: drop the `*_unsynced` ImageView; drop `ic_cloud_upload` and the `row_unsynced`/`row_refused` strings (en, fr)
- [x] `Ui.syncMark` and its callers in `MailList`, `ContactsList`, `CalendarList`, `FilesList`; the refused state of `MailList.state`
- [x] `MailStore.StoredMessage`, `EventStore.StoredEvent`, `PimdirContacts.Indexed`, `Entry`, `Group`: drop `unsynced`/`refused`; `MailStore.row` no longer reads the store per row
- [x] `PimdirStorage.unsynced` and `linkOfHandle` (no caller left)
- [x] `Refusals` deleted; `PimdirEngine.refuse`/`forgetRefusals` gone, the engines answering a plain rejection
- [x] Unit tests: assertions on the mark or `Refusals` dropped (MailMoveTest, MailBodiesTest, DeletedItemsTest), BackgroundCheckTest on the narrower constructor, `aCardIsUnsyncedUntilItsServerAgreesOnIt` deleted
- [x] e2e suite: pending and pushed read off the store by `E2eDevice.owes`
- [x] Spec folded, log written, CHANGELOG amended
