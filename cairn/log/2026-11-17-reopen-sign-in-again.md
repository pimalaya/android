---
cairn: log
change: reopen-sign-in-again
landed: 2026-11-17
---

# Reopen Sign in again after Back

Leaving the servers page with either back action closes the auth overlay but leaves its flipper on STEP_SIGNIN. On a later Sign in again, the account settings close and showPage sees that remembered step, so it skips opening the overlay and leaves the main screen visible.

OnboardingFlow.openOn now explicitly shows the auth panel after building the servers page, using the existing SetupShell.showAuth. Ordinary page refreshes, credentials, discovery cancellation and OAuth behavior are unchanged. This is a trivial navigation fix, with no separate proposal.

BrowserLeftTest adds a regression using its existing flow fixture: leave the servers page, invalidate discovery, reopen the stored account, and require a new showAuth call even though the step is unchanged. The test was added but not executed.

Android Studio analysis found no errors in the two edited Java files, with warnings. The Windows wrapper build and test-compilation command, `gw ./android/gradlew.bat -p android :app:assembleDebug :app:compileDebugUnitTestJavaWithJavac --console=plain`, timed out after 30 seconds, last reporting :app:mergeProjectDexDebug. Neither build completion nor test compilation is confirmed. The existing universal APK still had its earlier modification time when checked. No unit tests or device checks ran.

git diff --check passed after the code, test, spec and changelog edits. The subsequent `bash cairn/verify.sh --block` check timed out after 30 seconds with `fork: Resource temporarily unavailable`; it did not establish conformance.

Capabilities moved: onboarding. CHANGELOG.md records the user-facing fix.
