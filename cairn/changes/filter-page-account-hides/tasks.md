---
cairn: tasks
change: filter-page-account-hides
---

# Tasks

- [x] Filter rows on the theme's ripple; no other use of `list_selector_background`
- [x] `MergedFilter`: an account's box blank only when hidden, a collection's box its own choice, re-ticking restores choices as they were
- [x] `FilterPage`: a hidden account lists no collections
- [x] JVM tests: hidden account filters out and folds, collection choices kept, re-tick restores
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`
- [ ] Device test
- [ ] Fold the delta into `spec/offline-store.md` and log
