---
cairn: tasks
change: onboarding-options-ask-on-switch
---

# Tasks

- [x] Strings, English and French: the two phone switches and their lines, the notifications line; the settings rows reuse them
- [x] `SetupSwitches`: the setups' options, off by default, answers turning a refused switch off
- [x] Setups: phone switches ask on turn-on; Continue asks nothing
- [x] Setups: "Notify new mail" under Mail, standard and advanced, asking on turn-on; committed for an account covering mail
- [x] `BackgroundCheck`: notifications off by default; turning them on turns background sync on; the permission per SDK; `BackgroundJob` reads it
- [x] `MainActivity`: `askNotifications(callback)` beside `askMirrors`; drop the ask-once and `requestNotifications`
- [x] A denial Android will not prompt for again: switch off, a dialog pointing to Android settings with "Open settings"
- [x] Account settings: the three options read off without their permission; the notifications switch waits for the answer; a book turned on never prompts
- [x] Docs: the orchestration's decision table, PRIVACY.md; the earlier changes stating the old rule
- [x] Tests: `SetupSwitchesTest`, `BackgroundCheckTest`, `PermissionAnswerTest`
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`; `cairn/verify.sh`
- [ ] Device test: each switch on, granted and refused, in both setups and in settings; a permission revoked in the system settings
- [ ] Fold the delta into the spec, log, CHANGELOG

## Notes

- **Where each permission is asked now.** Contacts: the setups' "Show in phone contacts" switch, and in settings a book's "Show in phone contacts" box (or a book turned on, only when the permission is already held, so never a prompt). Calendar: the setups' "Show in phone calendar" switch, and a calendar's box in settings. Notifications: the setups' "Notify new mail" switch, and the settings' one. All through `MainActivity.askMirrors` / `askNotifications`, whose answers come back through `onRequestPermissionsResult`.
- **The answer.** `SetupSwitches.mirror` / `notify` turn a setup switch on only when granted; the toggle is set from what they answer, so a refusal flips it back. A permission already held answers at once, with no prompt.
- **Android's own refusal.** After two refusals Android stops prompting. When an answer is a denial and `shouldShowRequestPermissionRationale` is false for a missing permission (`PermissionAnswer.blocked`, JVM-tested), the switch stays off and a dialog in the app's `AlertDialog` style says "Android will not ask again. You can allow it in Android settings." with "Open settings": the app's details page for contacts and calendar, its notification settings for notifications. One path in `MainActivity.onRequestPermissionsResult`, so the setups and the settings share it (supervisor review, 2026-10-10).
- **The settings' remote-sync box** writes the book directly, keeping its phone flag, so toggling it never prompts nor drops a book whose permission was revoked.
- **Tests:** `SetupSwitchesTest` (off at start and on every run, granted on, refused off, one mirror's grant not counting for the other, off whatever is granted, notifications), `BackgroundCheckTest` (off until turned on, turning on sets 15 minutes when off and keeps a chosen interval, off leaves sync alone, forgotten, the permission per SDK). `PhoneMirrorTest` unchanged.
