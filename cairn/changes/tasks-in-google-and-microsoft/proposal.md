---
cairn: change
id: tasks-in-google-and-microsoft
status: active
created: 2026-11-06
---

# Tasks in Google Tasks and Microsoft To Do

## Why

Google and Microsoft keep an account's tasks in services apart from their calendars (Google Tasks, Microsoft To Do), which the app did not read: on such an account the Tasks tab stayed empty and the capture sheet had nowhere to file (owner, 2026-11-06, after testing both). io-gtasks (new, Tasks API v1) and io-msgraph's To Do module were built for it, both with a VTODO projection.

## What

- **Lists as collections**: each To Do list and Google task list is a calendar collection taking tasks alone, at `<base>/tasks/<list id>`, listed beside the account's calendars. The bridge routes every calendar verb on such a URL to To Do or Google Tasks, so the sync engine, the store and the Tasks tab take them as they take a CalDAV calendar. A task list is never an event's target nor one of the phone's calendars.
- **Microsoft To Do**: a first round lists a list by delta and keeps its delta link; bodies are read by `$batch` with io-msgraph's stash, which keeps server-side every VTODO line Graph has no field for (who a task waits on, its parent, the mail it came from). Waiting and Someday are To Do's own statuses (`waitingOnOthers`, `deferred`) while a task is open and states no other; Inbox and the person's categories are To Do categories. Writes check the revision first and patch what differs from Graph.
- **Google Tasks**: no sync token, so a round lists what was updated since the greatest `updated` seen, deleted and hidden tasks included, and a round a day old starts over in full to catch deletes Google stopped listing. A Google task keeps a title, notes, a date-only due, a done flag and a parent: its state, categories, who it waits on and the mail it came from stay on the phone, laid back over each read from the phone's copy (owner's choice, 2026-11-06: notes are no reliable place for them). The task page, the capture sheet and the account settings say so where those are set. Writes go `If-Match`.
- **Sign-in**: the shipped Google and Microsoft grants ask for the task service beside the calendar (`https://www.googleapis.com/auth/tasks`, `Tasks.ReadWrite`). Optional: a token without it, an account added before, lists its calendars alone, and its settings say adding it again brings the task lists.
- **Dates** read and write in the device's zone, which every network call now carries.

## Limits

- A task moved between Google lists in Google's own apps leaves no trace in the list it left: until that list's daily full round, it shows in both.
- Google tasks' states and categories do not survive a reinstall or reach another device.
- An account added before this version needs adding again for its task lists: the app has no "sign in again" path yet.
- Google verification: the Tasks scope is sensitive, so it joins the verification the Calendar and Contacts scopes already need.
