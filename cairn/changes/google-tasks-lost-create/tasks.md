---
cairn: tasks
change: google-tasks-lost-create
---

# Tasks

- [x] Bridge: a pure call telling whether two VTODO bodies read the same in the fields Google keeps; Rust tests (gtasks.rs `same_task`, through io-gtasks' own `GtasksTask::from_ical`, so due compares by date and `IN-PROCESS` or `CANCELLED` read as not done)
- [x] Naming: on a Google Tasks list, an unbound arrival named after the matching pending create, once per create; a Robolectric test with a create whose answer is lost (CalendarEngine `lostCreates`, GoogleTaskSyncTest). The arrival carries the create's staged body, not Google's copy: Google's copy has the task id as UID, so pimdir recorded a conflict and its merge held two VTODOs; with the staged body it lands as an answered insert does
- [ ] Live: on the Google test account; its delegation grants the tasks scope (checked 2026-11-07: a token for google@pimalaya.org lists its task lists; the note here that it did not was wrong)
- [ ] Fold, log, CHANGELOG
- [x] A two-sided edit on a Google task created on the phone conflicts on the whole task (tested 2026-11-07); laying the phone's UID back broke subtask links, so the owner chose to say it rather than fix it (google-tasks-limits-said)
