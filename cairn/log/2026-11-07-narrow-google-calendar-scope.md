---
cairn: log
change: narrow-google-calendar-scope
date: 2026-11-07
---

# Google's calendars asked as their events and their list

Decided by the owner after the scope audit (oauth-verification, scope-audit.md): the app manages no calendar, so the full `calendar` scope ("See, edit, share, and permanently delete all the calendars") had no use `calendar.events` and `calendar.calendarlist.readonly` miss, and a reviewer could have mailed a downscoping. Both classes stay the same (MGC restricted, the calendar scopes sensitive).

- The sign-in identity check reads the primary calendar's id with `calendarList.get("primary")` (io-gcal `GcalCalendarListEntryGet`), not `calendars.get`, which the pair does not cover; `Identity::of` keys the calendar identity on the calendar list's scope or the full one, so grants from earlier releases are still checked.
- Oauth.java asks the pair wherever `calendar` comes from (io-pim-discovery's Google rule, still naming it, or an earlier grant through the union), and OnboardingFlow narrows each page's consent needs the same way: left unnarrowed, a calendar would land on a grant missing the calendar list.
- e2e: the `google-gcal` token asks the pair; its fixture, creating and deleting calendars, adds `calendar.calendars`. Not run: the service account's domain-wide delegation must list the three first.
- Regressions: `a_calendar_grant_for_another_address_is_refused` (Rust) fails against the old call (path `calendars/primary`) and the old keying (no check); NarrowCalendarGrantTest fails against the old scope set (asked `calendar tasks`) and with the needs unnarrowed (the calendar landed). `cargo test` (645 passed), `:app:assembleDebug` and `:app:testDebugUnitTest` green. Not tried against Google live: consent with the pair, and what an older grant's re-consent returns with `include_granted_scopes`.

Capabilities moved: onboarding (requirement "Google's calendars are asked as their events and their list", added).
