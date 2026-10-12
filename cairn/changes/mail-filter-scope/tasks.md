---
cairn: tasks
change: mail-filter-scope
---

# Tasks

- [x] Review the proposal (the user)
- [x] Verify the archived-message re-read on the Gmail step: confirmed, `gmail_sync::step` lists from the newest and reads what is not held in the stepped mailboxes, an archived message held in none
- [x] Remove `MainActivity.listAlone` and `MailList.alone`
- [x] `MailList.footerOf`: Trash and Junk shown alone offer *Load more*; `RemotePass.loadMore` steps by label on Gmail, widens elsewhere
- [x] Filter defaults: Trash and Junk unticked once (`MergedFilter.quiet`), Reset back to that
- [x] Gmail: hidden All Mail (`MailStore.ALL_MAIL`), every step item landing in it, kept off the filter page, the chips and the rows
- [x] Drawer's and background mail skip what the filter unticks (`SyncScope.mail`, `BackgroundJob.scope`)
- [x] Pull under a chip syncs only that role's mailboxes of the shown accounts
- [x] *Load more* no longer steps the outbox (*No mailbox named Boîte d'envoi*)
- [x] Spec delta folded (mail.md, offline-store.md), log, CHANGELOG
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`
- [ ] Device test (the user): Gmail Trash chip shows without syncing, pull under it lists it, *Load more* under it; an archived-heavy Gmail account's *Load more* reaches older mail; Microsoft *Load more* with no outbox error
