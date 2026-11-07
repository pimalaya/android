---
cairn: tasks
change: drop-google-dav
---

# Tasks

## 1. App

- [x] Discovered Google CardDAV and CalDAV services dropped when the provider rule is Google's (`SetupPlan.offered`, read by `discoveredOptions`, so both setups)
- [x] The app's own Google CardDAV row removed from `addProviderOptions`, with `providerFound`
- [x] `GOOGLE_SCOPE` (carddav) removed, out of `GOOGLE_SCOPES` and the domain matching (Oauth.java); `apidata.googleusercontent.com` out of `GOOGLE_HOSTS` and the bridge's twin (rust/src/account/identity.rs); OauthTest, EndpointsTest, SetupPlanTest and the identity test follow
- [x] `AccountClient.googleCarddavBase` and the `googleCarddav` base URL kind (rust/src/account.rs) removed, with their tests
- [x] Generic DAV handling and its tests untouched (Location naming, no ETag, calendars outside the home set, MOVE refused)
- [x] e2e: `google-carddav` and `google-caldav` retired, the Google-only branches of E2eContactsTest and E2eCalendarTest with them; a `google-people` target owed in e2e-suite
- [x] `:app:assembleDebug` and `:app:testDebugUnitTest` green, `cargo test` in rust/ (644 passed)

## 2. Docs and plan

- [x] Spec folded (onboarding added requirement and the Google address scenario, offline-store scenarios generic), log written, CHANGELOG (Removed)
- [x] docs/providers.md Google section: APIs only; the DAV findings kept as the reason
- [x] oauth-verification: `carddav` out of submission.md §2, the CalDAV caveat out of the `calendar` justification, the carddav Decision closed, Data Access ticked; the video had no CardDAV segment

## 3. Console (the owner)

- [x] "Pimalaya" (2026-11-07): Gmail, Calendar, Tasks and People APIs enabled, CardDAV and CalDAV left off; Data Access holding the four scopes, `carddav` never added
