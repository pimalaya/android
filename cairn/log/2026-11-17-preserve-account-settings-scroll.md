---
cairn: log
change: preserve-account-settings-scroll
landed: 2026-11-17
---

# Keep account settings position after Sign in again

AccountSettings.open() previously scrolled to the top and hid the toolbar's account title on every opening, including the shared return from onboarding. Selecting Sign in again now remembers which account's settings should retain their position. The next opening consumes that marker: a matching account keeps the existing ScrollView position and title state while its settings are rebuilt; any ordinary opening still resets to the top. The marker is in-memory only and does not change the existing settingsEmail guard for callbacks after leaving the page. No RecyclerView, dependency, public API change or MainActivity navigation change was needed.

The human approved the proposal before implementation. The existing SignInAgainTest Robolectric fixture now checks returning after selecting the row, retaining scroll and toolbar title, keeping the stored credential unchanged, and resetting both scroll and title on a subsequent ordinary opening. The existing Sign in again reopening correction is preserved.

After explicit approval to repeat the earlier timed-out build with a longer limit, the Windows Gradle wrapper completed :app:assembleDebug and :app:compileDebugUnitTestJavaWithJavac successfully in 26 seconds. The APK is android/app/build/outputs/apk/debug/app-universal-debug.apk. Both edited Java files passed Android Studio analysis without errors, with warnings and suggestions remaining; git diff --check passed. Unit tests were compiled but not executed, and no app installation or device/UI check was performed. Native-backed unit-test execution still requires the unavailable Nix environment; its safeguards were not bypassed.

Capabilities moved: onboarding. CHANGELOG.md records the user-facing correction.
