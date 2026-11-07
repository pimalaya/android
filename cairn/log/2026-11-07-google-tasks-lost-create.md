---
cairn: log
change: google-tasks-lost-create
date: 2026-11-07
---

# A Google task whose create was lost is filed once

Capabilities moved: tasks (added: A Google task whose create was lost is filed once).

Google Tasks keeps no UID, so a create whose answer was lost came back as a stranger and was pushed again, a second task on Google. On a Google task list, an unbound arrival reading the same as a pending create of that list in title, notes, due day and completion (rust/src/calendar/gtasks.rs `same_task`, through io-gtasks' own `GtasksTask::from_ical`) now lands that create (CalendarEngine `lostCreates`, pending_create_objects.sql), carrying the create's staged body so it lands as an answered insert does. Each create names one arrival; a bound task is never claimed. Built in 332a1c5.

Live on the Google test account, 2026-11-07: Google keeps every compared field exactly as sent (spaces, CRLF, decomposed accents, done, empty notes, a past due day). A title over Google's cap is refused (400 "Title is too long"), outside this change.

Found while building it: a two-sided edit on a task created on the phone conflicts on the whole task (its stored body keeps the phone's UID, Google's copy the task id), and a subtask made on Google under such a task is not shown under it; the owner chose to say these (google-tasks-limits-said) rather than work around them.
