---
cairn: tasks
change: mail-filter-apply
---

# Tasks

- [x] Review the proposal (the user)
- [x] Defaults: every mailbox but the inbox unticked once off Gmail (`MergedFilter.quiet`), new accounts only
- [x] `FilterPage`: ticks as a draft, *Apply* button, the cross drops it; strings en and fr
- [x] Apply: first chunk of each newly ticked mailbox never listed (`RemotePass.firstOf`), a pull for a newly ticked book or calendar
- [x] Untick stops every sync of the mailbox: pull, drawer, background, body download (`planBodies`), fill, *Load more*
- [x] First sync: the account's step over the ticked mailboxes only; JMAP via `inMailboxOtherThan` (bridge)
- [x] *Load more*: one step per ticked mailbox off Gmail; footer *About 50 more per mailbox*
- [x] Tests: defaults per provider, the draft, the own frontier off Gmail
- [x] Spec delta folded (mail.md, offline-store.md), log, CHANGELOG
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest` (531), `cargo test` (499)
- [ ] Device test (the user): Microsoft onboarding lists the inbox alone; ticking Sent and Apply lists it; *Load more* per mailbox; the cross drops ticks; Gmail unchanged
