---
cairn: tasks
change: audit-s1-residuals
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, client ones to android/client/src/main/java/org/pimalaya/client/.

- [ ] I1 BackgroundJob.notify (BackgroundJob.java:96-150): `VISIBILITY_PRIVATE` and a `setPublicVersion` reading "N new messages" on each message and on the summary; channel name the account label, channel id unchanged; strings en and fr
- [ ] I2 "Hide in recents" setting: `setRecentsScreenshotEnabled(false)` on API 33+, `FLAG_SECURE` below; off by default; strings en and fr
- [ ] I3 account/SecureStore.java `seal`/`open` (lines 270, 282) take a purpose bound as AAD ("accounts", "oauth-grant"); PendingGrantStore.java:48 passes its own; a legacy blob opened without AAD once and sealed again; test that an accounts blob does not open as a grant
- [ ] I6 MailSession.java: verbs and `close` (lines 63-111) serialized on the session, or the handle released through an `AtomicLong` swap; a test closing while a verb runs
- [ ] I7 phone pass: a too-many-deletions guard per book and calendar (`SyncResult.stats`, `tooManyDeletions`), the deletions held until the user confirms; `AGGREGATION_MODE_DISABLED` on raw contacts of a book the user does not own
- [ ] L1 SyncService.java:59 and CalendarSyncService.java:58: `super(context, false)`
- [ ] L3 nit sync/SyncCoordinator.java:1345, 1394, 1417: drop the URL from the warnings
- [ ] Tests: Robolectric for the notification visibility and public version, the AAD migration, the deletion guard
- [ ] Build: assembleDebug, app and client unit tests, cairn/verify.sh
- [ ] Fold, log, CHANGELOG
