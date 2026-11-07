---
cairn: tasks
change: google-data-notice
---

# Tasks

- [x] GoogleNotice: the dialog, its text built from the scopes asked, the privacy link; Agree runs the grant, every other close declines it
- [x] OnboardingFlow: the Google branch of `startGrant` shows it before `startGoogleOauth`, the loader off until Agree, a decline resetting the page as an abandoned consent does
- [x] Oauth: the Google scope constants the notice reads made public, `GOOGLE_MAIL_SCOPE` added
- [x] Strings, English and French
- [x] GoogleNoticeTest (the notice before anything opens, Agree, backing out, the lines per scope), failing against the old direct grant; BrowserLeftTest, NarrowCalendarGrantTest and WrongGrantTest tap Agree
- [x] `:app:assembleDebug` and `:app:testDebugUnitTest` green (905)
- [x] PRIVACY.md's Google section follows; spec folded, log, CHANGELOG
- [ ] Owner: the website's copy of PRIVACY.md synced and published (`npm run sync:privacy` in the websites repo, then the Publish workflow of pimalaya/_www)
- [ ] Owner: the notice seen on a device, its text read through in both languages
