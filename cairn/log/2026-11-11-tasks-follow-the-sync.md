---
cairn: log
change: tasks-follow-the-sync
date: 2026-11-11
---

# The Tasks tab follows the calendars' syncs

Found while recording the Google verification video (docs/providers.md, 2026-11-11): a task created, synced from the drawer, then ticked in the list failed with "Pimdir MUTATE failed: unknown handle <uuid>.ics" until the task was opened once; the same after a rename synced. And the Tasks list had no pull-down.

- Root cause: a calendar pass ends with `screens.reload(PimDomain.CALENDAR)`, and MainActivity.reload and afterBackgroundRun reloaded the agenda alone. The Tasks rows kept the provisional handle of a create (U+0001 then `<uuid>.ics`, PimdirStorage.rowHandle) that the pass had bound to Google's id; the store's handle lookup no longer reaches it, and io-pimdir answers `UnknownHandle`, as its spec says. Opening the task reloaded the list, hence the workaround.
- MainActivity reloads the Tasks list with the agenda, after a calendar pass and after a background run.
- panel_tasks.xml wraps the list in a SwipeRefreshLayout (`tasks_refresh`), wired as the agenda's; TasksList takes the pull, MainActivity passes `syncCalendars`, so it syncs the calendars within the filter, events included, as the agenda's pull does. No new dependency.
- Tests failing first (TasksTabSyncTest, the real activity and store under Robolectric, a fake Google filing the task as `g1`): `aTaskTickedAfterThePassFilingItIsDone` (before: "unknown handle ?efc0609a-....ics"), `pullingTheTasksListSyncsTheCalendars` (before: the list sat in a FrameLayout).
- Checked: `:app:testDebugUnitTest` (910 tests, 0 failures, 5 skipped), by the agent that wrote it in its worktree; merged here and run again with the other fixes of the day.
- Not done, found on the way: a task page, a Clarify step or an event view left open during a pass still writes with the handle and body it read at load (TaskWrites, EventWrites, EventView), and no `if_match` reaches the mutate, so such a write can fail the same way or silently undo what the pass brought in. Mail and contacts look the handle up at write time. Proposed as its own change (write-against-the-current-revision).

Capabilities moved: tasks (The Tasks tab follows the calendars' syncs, added), offline-store (A list's pull syncs what it shows, scenario added).
