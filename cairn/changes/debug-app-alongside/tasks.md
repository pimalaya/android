---
cairn: tasks
change: debug-app-alongside
---

# Tasks

## 1. App

- [ ] `applicationIdSuffix = ".debug"` on the debug build type, and a per-build-type `app_name` ("Pimalaya Dev" on debug)
- [ ] Account types per build type: a BuildConfig field read by `Accounts.TYPE` and `Accounts.CALENDAR_TYPE`, a generated string resource read by authenticator.xml, authenticator_calendar.xml, syncadapter.xml and syncadapter_calendar.xml
- [ ] Microsoft redirect scheme per build type (BuildConfig field and manifest placeholder, as Google's), read by `Oauth.MICROSOFT_REDIRECT_URI` and the manifest's intent-filter
- [ ] Dynamic-registration redirect built from the application ID, read by `Oauth.REDIRECT_URI` and the manifest's intent-filter
- [ ] shortcuts.xml: a debug copy under android/app/src/debug/res/xml naming `org.pimalaya.debug`
- [ ] Every other literal `org.pimalaya` a second install would collide on checked (the `org.pimalaya.action.*` intent actions are explicit, so expected harmless); tests reading the literal account types or redirects (PendingGrantTest, LayeringTest) follow the build type
- [ ] Merged manifests checked: debug carries `org.pimalaya.debug`, its account types and redirects; release unchanged from today
- [ ] `:app:assembleDebug`, `:app:assembleRelease` and `:app:testDebugUnitTest` green

## 2. Console (the owner)

- [ ] Entra registration: the debug Microsoft redirect added under Mobile and desktop applications, beside `pimalaya://oauth2redirect`
- [ ] "Pimalaya Dev" Android client: package `org.pimalaya.debug`

## 3. On the phone (the owner)

- [ ] The old debug install (`org.pimalaya`, debug key) uninstalled, the new debug build installed beside a release build; a Google, a Microsoft and a Fastmail sign-in each tried on both, each redirect landing in the app that started it

## 4. Land

- [ ] Spec folded, log written, CHANGELOG (Changed: debug builds install beside release builds), archived
