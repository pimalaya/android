---
cairn: change
id: google-tasks-limits-said
status: active
created: 2026-11-07
---

# What Google Tasks cannot keep is said

## Why

Google Tasks is the least capable task backend: its API keeps a title, notes, a due day and done or not, and no UID. The owner decided on 2026-11-07 to tell users its limits rather than work around them. Today the task page and the account's Tasks section say only that states and categories stay on the phone (`task_google_local`, `account_tasks_in_google`), while building google-tasks-lost-create showed more a user meets:

- A task's state (Inbox, Waiting, Someday), categories, priority, who it waits on and the mail it came from stay on this phone alone: another device or a reinstall loses them.
- Google keeps a due day, not a time.
- In progress reads as open on Google, Cancelled as done.
- A task changed both here and on Google between two syncs comes back as a conflict on the whole task, not on the field both changed (the phone's copy keeps its own UID, Google's carries its task id; laying the phone's UID back broke subtask links, so it was left, 2026-11-07).
- A subtask made on Google under a task made on this phone does not show under it (Google names the parent by its task id).

## What

- **One dialog, *What Google Tasks keeps***, listing the five points above in plain words, opened from the task page's *On this phone only* row (a tap, with a chevron) and from the account settings' Tasks row of a Google account.
- The row's own line stays short: "Google Tasks keeps the title, notes, due day and done. Tap for what stays on this phone."
- No workaround is built for any of them.

## Design

On the canvas, light and dark: the task page's row with its chevron, and the dialog.
