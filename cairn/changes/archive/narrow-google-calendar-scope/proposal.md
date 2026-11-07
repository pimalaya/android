---
cairn: change
id: narrow-google-calendar-scope
status: landed
created: 2026-11-07
---

# Google's calendars asked as their events and their list

## Why

The app's Google client asked for `https://www.googleapis.com/auth/calendar`, whose consent line reads "See, edit, share, and permanently delete all the calendars you can access using Google Calendar". The app never creates, renames, deletes or shares a calendar: it lists the calendar list and reads and writes events (oauth-verification, scope-audit.md). Google's verification asks why a narrower scope would not do, and its reviewers mail a "downscoping" when one would, a round trip of weeks. The one call the full scope covered and the narrow pair did not was the sign-in identity check's `calendars.get("primary")`.

## What

- The identity check reads the primary calendar's id off the calendar list (`calendarList.get("primary")`, rust/src/calendar/gcal.rs), under the calendar list's scope or the full one (rust/src/account/identity.rs).
- The shipped Google client asks `calendar.events` and `calendar.calendarlist.readonly` in place of `calendar` (Oauth.java: `GOOGLE_SCOPES`, `fixedScope`, `withTasks`, `domainScope`), wherever the full scope comes from: io-pim-discovery's Google rule, or a grant from an earlier release.
- A calendar page's consent needs are the narrowed pair (OnboardingFlow), so a calendar signs in only when both come back.
- e2e: the app's token asks the pair; the Google calendar fixture, which creates and deletes calendars, adds `calendar.calendars`.
- Owner: the Data Access page lists the pair in place of `calendar`; the e2e service account's domain-wide delegation lists the pair and `calendar.calendars`.

Grants from earlier releases hold `calendar`, which covers every call, so they keep syncing; their next sign-in asks for the pair.
