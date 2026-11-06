---
cairn: tasks
change: phone-deletions-held
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [x] pimdir/HeldDeletions.java: the rule, the held rounds, release and restore, asked once per counts; `PimdirDb.held()`
- [x] pimdir/PhonePass.java: the hold in `enumerate`, the purge after each exchange, the restore before the quiet path; `PhoneSpoke.purge`
- [x] contact/PhoneRemote.java and calendar/CalendarRemote.java: deleted rows kept until purged; `PimdirStorage.forget` by handles
- [x] sync/SyncCoordinator.java `offerHeld`: the dialog, en and fr
- [x] Tests: CalendarMirrorTest (a wipe held, released, restored, a few deletions unasked)
- [ ] Contacts: no fake ContactsContract provider yet, so the contacts spoke's deferred purge and restore are checked on the device only
- [ ] Device check: mirror a book or calendar of 20 or more, delete them all in another app, sync: the dialog, then each of its three answers
- [ ] Fold, log, archive
