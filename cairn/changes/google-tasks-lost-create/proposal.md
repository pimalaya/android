---
cairn: change
id: google-tasks-lost-create
status: active
created: 2026-11-06
---

# A Google task whose create was lost is filed once

## Why

A create whose answer was lost stays staged, and the next pass lists the server's copy. Where the server keeps the object's UID, that copy arrives named by it and pimdir lands the pending create on it (CalendarMirrorTest `aCreateWhoseAnswerWasLostIsFiledOnce`). Google Tasks keeps no UID: a task's identity is Google's task id (rust/src/calendar/gtasks.rs), so the copy arrives as a stranger, and the pending create is pushed again, a second task on Google. The owner chose a match on content, nothing visible in Google Tasks (2026-11-06).

## What

- On a Google Tasks list, an arrival the store binds to nothing is named after a pending create of that list whose task reads the same: title, notes, due date and completion, the fields Google keeps. Then pimdir lands the create on it, as `MailEngine.nameByMessageId` does for a sent copy Gmail files.
- Only unbound arrivals and pending creates take part, so a task the store already holds is never claimed by a create.
- The comparison is the bridge's (Rust), over the two bodies; Java only pairs the candidates.

Rejected: listing the list before each create and binding a task that reads the same, which could claim a task another item of the store already holds, and costs a request per create.

Accepted risk: the same task written on Google and on the phone between two syncs becomes one.
