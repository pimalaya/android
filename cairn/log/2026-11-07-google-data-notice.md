---
cairn: log
change: google-data-notice
date: 2026-11-07
---

# The app's notice before Google's consent page

The last app-side piece of Google's verification (oauth-verification): the disclosure of the Workspace API user data policy, checked against its text on 2026-11-07 (rules 1.1 to 1.7 and 2.1 to 2.4 of "Transparent and accurate notice and control") and Google Play's User Data policy.

- GoogleNotice, a dialog over the sign-in page: the app's name; one line per asked Google data with its uses; the address read to check the sign-in, only where the grant reads one (mail, the calendar list), a contacts-only grant reading none; the data going only between Google and the phone, stored there, never received by Pimalaya, sold or used for ads; the phone mirrors where calendars or contacts are asked; a link to https://pimalaya.org/android/privacy/. Agree and Not now, as Play's best practices word them.
- OnboardingFlow shows it in the Google branch of `startGrant`, after the grant's scopes and consent needs are known, so it lists what is asked; the loader waits for Agree. A decline (Not now, back, a tap outside, any other close) clears the grant as an abandoned consent page does, and Continue shows the notice again. Every Google consent passes there, a sign-in again's too; no other provider does.
- PRIVACY.md's Google section names Google Tasks, the labels, the sending addresses and the address check, and says the notice precedes Google's page. The website's copy is synced and published by the owner.
- Regression: GoogleNoticeTest fails against the old direct grant (no notice, the consent page opened at once). BrowserLeftTest, NarrowCalendarGrantTest and WrongGrantTest tap Agree first. `:app:assembleDebug` and `:app:testDebugUnitTest` (905) green. Not seen on a device yet.

Capabilities moved: onboarding (requirement "Google's consent page follows the app's notice of what it reads", added).
