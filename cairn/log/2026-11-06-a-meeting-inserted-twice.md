---
cairn: log
change: a-meeting-inserted-twice
date: 2026-11-06
---

# A meeting inserted twice is the event already there

A fix to a production-release section 1 check, folded without a proposal.

Capabilities moved: calendar (modified: a Google calendar can run over the Calendar API, a duplicate insert taking the event already filed).

Probed live on 2026-11-06 with the service account, on a scratch calendar: `events.insert` under an `iCalUID` the calendar holds answers 409 `duplicate` ("The requested identifier already exists."), `events.import` under one answers 200 with the same event, and listing by `iCalUID` finds it. A meeting the user organizes is inserted, so one whose answer was lost stayed staged and failed with 409 on every sync. `create_entry` now reads the live event back by its `iCalUID` on that 409 and binds it. Test: `a_meeting_inserted_twice_is_the_event_already_there` (rust/src/calendar/gcal/tests.rs). The live e2e run on google-gcal passed the create and delete cases; its round trip fails in the harness itself (e2e-suite).
