---
cairn: tasks
change: sync-feedback-pinned
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [x] `MailTally`: planned, settled and in-flight counts, a directory watch counting the files a bridge call writes
- [x] `MailEngine.tally`: `stepRead` watching `mailStep`, `fetchBodies` watching `fetchMessageSources`; `GmailStep`, `GraphStep` through `stepRead`; `ImapStep` plans the bodies it reads
- [x] `RemotePass.stepMail` tells the tally's count as the step's download; `MainActivity.planSteps` weighs each stepped account alike (`firstMail`, `loadMore`)
- [x] Background bodies: one tally over the run (`planBodies`, the fill pool's engines, settled per chunk), the strip's bar in fill mode
- [x] Layout: sync_strip.xml pinned in panel_mail, panel_contacts, panel_calendar; list_header.xml loses the strip and the glyph; `ListHeader` renders sync, waiting and fill modes into it
- [x] Footer: `MailList.FooterState` (load, loading, waiting for Wi-Fi), *Loading more*, redrawn as a sync starts and ends
- [x] Strings en and fr: `mail_more_loading`
- [x] Tests: `MailTallyTest` (counts, watch, settle and replan), a Gmail and an IMAP step's count, footer states
- [x] CHANGELOG [Unreleased]
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`; cairn/verify.sh
- [x] Fold the delta into spec/mail.md and spec/offline-store.md, log entry
- [ ] Device test (the user): *Load more* on Gmail from the end of the list (bar filling, button reading *Loading more*), a first IMAP sync, the background bodies scrolled down
