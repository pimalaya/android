---
cairn: tasks
change: audit-s1-residuals
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, client ones to android/client/src/main/java/org/pimalaya/client/.

- [ ] I1 BackgroundJob.notify (BackgroundJob.java:96-150): `VISIBILITY_PRIVATE` and a `setPublicVersion` reading "N new messages" on each message and on the summary; channel name the account label, channel id unchanged; strings en and fr
- [ ] I2 "Hide in recents" setting: `setRecentsScreenshotEnabled(false)` on API 33+, `FLAG_SECURE` below; off by default; strings en and fr
- [ ] I3 account/SecureStore.java `seal`/`open` (lines 270, 282) take a purpose bound as AAD ("accounts", "oauth-grant"); PendingGrantStore.java:48 passes its own; a legacy blob opened without AAD once and sealed again; test that an accounts blob does not open as a grant
- [x] I6 MailPool is gone; the reachable path was sync/WorkerPool.java returning when its wait was interrupted (an activity destroyed mid-pass), its caller then closing sessions workers still used: the wait is uninterruptible, the interrupt stopping the run between keys and kept for the caller, `close` waits for a run, and `MailSession` verbs, `reopen` and `close` hold the session's lock; WorkerPoolTest
- [x] I7 the deletion guard, moved to phone-deletions-held (the app's own dialog: the platform's prompt only follows `onPerformSync`, which most passes do not run through)
- [ ] I7 a book the user does not own kept out of aggregation: not `AGGREGATION_MODE_DISABLED`, which leaves the raw contact out of every Contacts query; the book left unmirrored by default, or `AggregationExceptions` TYPE_KEEP_SEPARATE, once io-webdav reads `DAV:owner` (RFC 3744) and a book states whether the user owns it
- [ ] L1 SyncService.java:59 and CalendarSyncService.java:58: `super(context, false)`
- [ ] L3 nit sync/SyncCoordinator.java:1345, 1394, 1417: drop the URL from the warnings
- [ ] Tests: Robolectric for the notification visibility and public version, the AAD migration, the deletion guard
- [ ] Build: assembleDebug, app and client unit tests, cairn/verify.sh
- [ ] Fold, log, CHANGELOG
