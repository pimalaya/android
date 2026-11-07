---
cairn: tasks
change: narrow-google-calendar-scope
---

# Tasks

- [x] `Gcal::primary_id` reads `calendarList.get("primary")`; `Identity::of` reads a calendar identity under `calendar.calendarlist.readonly` or `calendar`; test `a_calendar_grant_for_another_address_is_refused`, failing against the old call and the old keying each
- [x] Oauth.java: `GOOGLE_SCOPES` holds the pair, `fixedScope` asks the pair for `calendar`, `withTasks` and `domainScope` take either; OauthTest follows
- [x] OnboardingFlow: a page's consent needs narrowed like the grant; NarrowCalendarGrantTest (the scope asked, failing against the old scope set; the calendar list unticked, failing with the needs left unnarrowed)
- [x] e2e: E2eTarget's calendar token asks the pair, its fixture adds `calendar.calendars`; rust/src/account/oauth.rs test string
- [x] `cargo test` in rust/ (645 passed), `:app:assembleDebug` and `:app:testDebugUnitTest` green
- [x] Spec folded (onboarding, added requirement), log, CHANGELOG (Changed); oauth-verification's proposal, tasks, scope-audit.md and submission.md §2 follow
- [ ] Owner: Data Access page lists the pair, not `calendar`
- [ ] Owner: the e2e service account's domain-wide delegation lists `calendar.events`, `calendar.calendarlist.readonly` and `calendar.calendars`, then `google-gcal` run
