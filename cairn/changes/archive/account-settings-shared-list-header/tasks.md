---
cairn: tasks
change: account-settings-shared-list-header
---

# Tasks

- [x] Obtain human approval of the proposal before implementation.
- [x] Move account settings to a native ListView adapter while retaining existing setting controls and removal safeguards.
- [x] Reuse ListHeader and route its title handoff to the overlay toolbar without disturbing underlying lists or FABs.
- [x] Replace the return marker with an explicit return path preserving the native list position and normal opening/reset behavior.
- [x] Adapt regression coverage for the list position, shared header handoff and fresh opening; compile but do not execute tests.
- [x] Run permitted focused IDE, build, whitespace and Cairn validation, recording any limits honestly. APK assembly and test-source compilation succeeded; whitespace and Cairn checks passed. IDE diagnostics outside the changed MainActivity code remain documented in the log. Unit tests and device/visual checks were not run.
- [x] Fold the delta into onboarding, update CHANGELOG.md, write the dated landing log and archive the landed change.
