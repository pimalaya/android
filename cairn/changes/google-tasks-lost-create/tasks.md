---
cairn: tasks
change: google-tasks-lost-create
---

# Tasks

- [x] Bridge: a pure call telling whether two VTODO bodies read the same in the fields Google keeps; Rust tests (gtasks.rs `same_task`, through io-gtasks' own `GtasksTask::from_ical`, so due compares by date and `IN-PROCESS` or `CANCELLED` read as not done)
- [x] Naming: on a Google Tasks list, an unbound arrival named after the matching pending create, once per create; a Robolectric test with a create whose answer is lost (CalendarEngine `lostCreates`, GoogleTaskSyncTest). The arrival carries the create's staged body, not Google's copy: Google's copy has the task id as UID, so pimdir recorded a conflict and its merge held two VTODOs; with the staged body it lands as an answered insert does
- [ ] Live: on the Google test account, once its delegation grants the tasks scope (the service account's domain-wide delegation lists none today)
- [ ] Fold, log, CHANGELOG
- [ ] A two-sided edit on a Google task created on the phone: its stored body keeps the phone's UID while Google's copy carries the task id, so the same merge may hold two VTODOs; a test first (found while building this, 2026-11-07, not made by it)
