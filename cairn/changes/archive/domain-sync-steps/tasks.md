---
cairn: tasks
change: domain-sync-steps
---

## App (android/)

- [x] `PimdirEngine.domain()` and the domain on `Progress.step`; `SyncRunner.Observer` passes it through
- [x] `SyncSteps`: a domain's text for a stage, none for a phone step outside contacts
- [x] Plurals for every counted step, English and French
- [x] Tests (`SyncStepsTest`): events for the agenda, messages for mail, contacts for the books, the phone steps the contacts' alone, French

## Land

- [x] `:app:assembleDebug`, `:app:testDebugUnitTest`
- [x] Fold into offline-store; log; CHANGELOG; archive
