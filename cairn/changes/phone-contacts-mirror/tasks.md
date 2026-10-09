---
cairn: tasks
change: phone-contacts-mirror
---

# Tasks

- [ ] Onboarding: the switch on the Contacts card, standard and advanced; `commitBooks` takes it
- [ ] Permission asked on continue and on the settings switch only; refused turns the switch off
- [ ] Detection of the same address under another account type; result page and settings line
- [ ] Debounced phone pass after every write to a mirrored book
- [ ] `setSyncAutomatically(true)` on reconcile, for new and existing accounts
- [ ] Phone pass on return for changed books
- [ ] Drop `runner.syncLocal` from the pull and the drawer's sync, the Local report line, the adb hook
- [ ] Switch on: reconcile and first projection on the strip; off: last pass, then removal
- [ ] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`
- [ ] Device test: edit in the Contacts app with Pimalaya closed, then open it; edit in Pimalaya, then open the Contacts app; a Gmail address signed in on the phone
- [ ] Fold the delta into the spec, log, CHANGELOG
