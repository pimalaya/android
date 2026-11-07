---
cairn: log
change: drop-google-dav
date: 2026-11-07
---

# Google over its APIs only, not CardDAV or CalDAV

The app no longer offers Google's CardDAV or CalDAV: Google's contacts connect over the People API and its calendars over the Google Calendar API, in the advanced setup as in the standard one. The "Pimalaya" project was set up to match the same day (owner): the Gmail, Calendar, Tasks and People APIs enabled, the CardDAV and CalDAV APIs left off, and Data Access listing `https://mail.google.com/`, `calendar`, `tasks` and `contacts`.

- SetupPlan.offered drops the CardDAV and CalDAV configs of io-pim-discovery's Google rule from the discovered options; OnboardingFlow's own Google CardDAV row is gone. io-pim-discovery is unchanged.
- Oauth.java: `GOOGLE_SCOPE` (carddav) is gone, out of `GOOGLE_SCOPES` and the contacts domain's scope; `apidata.googleusercontent.com` left `GOOGLE_HOSTS`, and the bridge's twin list in rust/src/account/identity.rs.
- `AccountClient.googleCarddavBase` and the bridge's `googleCarddav` base URL kind are removed.
- The generic DAV handling and its tests stay (a create filed under the server's own name, a PUT answered with no ETag, calendars outside the home set, a MOVE refused); two offline-store scenarios no longer name Google.
- The e2e suite's `google-carddav` and `google-caldav` targets are retired; a `google-people` target is owed (e2e-suite tasks).
- No migration: a tester's Google DAV domain keeps syncing while its grant holds, and is removed and added again to move onto the APIs.
- Checked: `cargo test` (644 passed), `:app:assembleDebug` and `:app:testDebugUnitTest`.

Capabilities moved: onboarding (Google connects over its APIs alone, added; A provider's own rules are discovered first, modified), offline-store (A placement no source binds is offered under its provisional handle, A write records the revision the server holds, both modified).
