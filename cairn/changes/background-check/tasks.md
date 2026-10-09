---
cairn: tasks
change: background-check
---

# Tasks

- [x] Move the mail and calendar passes into `RemotePass`
- [x] `SyncLock`: one sync at a time across the app and the job
- [x] `BackgroundCheck`: per-account switches, scheduling
- [x] `BackgroundJob`: the headless run, the inbox diff, the notifications
- [x] Manifest: the job service, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`
- [x] Settings: the Background card, the interval per account
- [x] The strip during a run, the first sync waiting for its end
- [x] App: reload after a run, clear notifications on open, ask the permission
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`
- [ ] Device test
- [ ] Fold the delta into the spec, log, CHANGELOG
