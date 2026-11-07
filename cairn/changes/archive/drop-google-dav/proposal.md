---
cairn: change
id: drop-google-dav
status: landed
created: 2026-11-07
---

# Google over its APIs only, not CardDAV or CalDAV

## Why

At Google the app speaks two ways to the same data: the People API or CardDAV for contacts, the Google Calendar API or CalDAV for calendars. The standard setup already takes the APIs alone (onboarding, "The standard setup connects with the best sign-in found"); Google's DAV is reached only from the advanced setup, where io-pim-discovery's provider rule names Google's CalDAV and CardDAV and the app adds its own CardDAV row (OnboardingFlow `addProviderOptions`). That second path costs more than it brings:

- Review: Google's verification of the shared "Pimalaya" project (oauth-verification) asks for every scope's justification and its use in the demo video. `https://www.googleapis.com/auth/carddav` asks for the contacts the `contacts` scope already covers, a duplicate the reviewer may question; the CardDAV and CalDAV APIs are two more APIs to enable and show.
- Calendar scope: CalDAV is what keeps `calendar` from narrowing to `calendar.events` with `calendar.calendarlist.readonly`, which the Calendar API path is known to need alone (oauth-verification, Decisions).
- Quirks: Google's DAV is second-rate DAV (docs/providers.md): a `sync-collection` with no token answers 400, so every pass falls back to a full listing; CardDAV deltas miss creates and deletes made over CardDAV; CalDAV ignores `If-Match` on DELETE; discovery redirects only an authenticated PROPFIND. The APIs bring sync tokens that work, contact groups and Google Tasks.
- No Pimalaya interface needs it from the shared client: the contacts and calendar command-line tools reach Google through io-gpeople and io-gcal.

## What

- The setups SHALL NOT offer Google's CardDAV or CalDAV: the app drops them from the discovered services when the provider rule is Google's, and its own Google CardDAV row goes. Google's contacts and calendars connect over the People API and the Google Calendar API alone, in the standard and the advanced setup.
- The shipped Google client stops asking for `carddav`: `GOOGLE_SCOPE` leaves `GOOGLE_SCOPES` (Oauth.java), its domain matching keeps the People scope alone, and `apidata.googleusercontent.com`, Google's CalDAV host, leaves `GOOGLE_HOSTS`.
- The Google-only wiring goes: `AccountClient.googleCarddavBase` and the `googleCarddav` base URL kind (rust/src/account.rs); Google's CalDAV host leaves the bridge's twin host list too (rust/src/account/identity.rs).
- The e2e suite's `google-carddav` and `google-caldav` targets go, testing a path no user reaches; Fastmail's DAV targets keep live DAV covered. Google's contacts are left with no e2e target until a `google-people` one exists (e2e-suite tasks).
- Generic DAV behaviours stay, with their tests: a server filing a create under a name of its own and saying so in `Location`, a PUT answered with no ETag, calendars listed outside the home set, a MOVE refused. They are lawful for any DAV server; the tests merely borrow Google's answers as fixtures. The spec scenarios citing Google as the example lose the name.

## Decisions

- io-pim-discovery stays as it is: its provider rule says what Google serves, which is true; which of those services an app offers is the app's choice. The filter lives in the app.
- Existing Google DAV domains (closed alpha, reached only through the advanced setup): no migration, as recommended (the owner can still ask for one). Such a domain keeps syncing over plain DAV while its grant holds, since a refresh names no scope; the next consent on that account (a domain added, a sign-in again) no longer covers it: `carddav` is no longer asked, and Google's CalDAV host is no longer one the app's Google client signs in to, so that consent falls to the generic client page. Testers who picked it remove the account and add it again; the CHANGELOG says so. The alternative, moving such domains onto the APIs on upgrade, is a migration for a handful of testers at most.
- Out of scope: narrowing `calendar` to `calendar.events` with `calendar.calendarlist.readonly`. This change makes it possible; whether to do it is oauth-verification's call, both being sensitive.
