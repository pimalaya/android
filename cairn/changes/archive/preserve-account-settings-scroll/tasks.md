---
cairn: tasks
change: preserve-account-settings-scroll
---

# Tasks

- [x] Obtain human approval of the intent before implementation.
- [x] Preserve settings scroll position and toolbar title state on the shared onboarding return path, while normal openings still start at the top.
- [x] Add a regression check in the existing Robolectric style, without executing unit tests unless authorized.
- [x] Run focused IDE and permitted build checks; report any timeout or unavailable environment without bypassing test safeguards. Both edited Java files had no IDE errors; assembleDebug and compileDebugUnitTestJavaWithJavac succeeded. Unit tests were not executed.
- [x] Fold the delta into onboarding, update CHANGELOG.md, write a dated landing log and archive the landed change.
