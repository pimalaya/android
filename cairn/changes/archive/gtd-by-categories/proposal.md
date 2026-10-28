---
cairn: change
id: gtd-by-categories
status: landed
created: 2026-10-28
---

# GTD by categories

## Why

The Tasks tab (archived change gtd, 2026-10-27) carries GTD in an `X-PIMALAYA-GTD` property: Inbox, Waiting, Someday and Project live where no other client reads or writes them, so a task captured or clarified elsewhere is invisible to the method, and the method is invisible elsewhere. GTD is a method, not a data model: what makes the Inbox, Waiting and Someday special is what the person does with them, not how they are stored.

This change keeps the whole experience and replaces the storage with the convention pimdir states (pimalaya/pimdir cairn/changes/tasks-by-categories): three reserved `CATEGORIES` values, `inbox`, `waiting` and `someday`, every other category the person's own. unfog follows the same convention (pimalaya/unfog cairn/changes/gtd-and-time-tracking), so a task captured on the phone is clarified in the terminal and back.

Nothing has been released with `X-PIMALAYA-GTD` (CHANGELOG [Unreleased]), so there is no migration.

## What

### The model

- A task's GTD state is its categories: `inbox`, `waiting`, `someday`, compared case-insensitively, stored as those English literals and shown under the app's labels in every locale.
- A next action is an open task with none of the three, open being any `STATUS` but `COMPLETED` and `CANCELLED`, a task stating no `STATUS` included: a to-do from another client is one, as before.
- Projects are no longer a GTD type: a project is a category like any other, which the person filters on. `RELATED-TO` keeps showing a subtask under its parent, as the agenda does, but decides nothing.
- `CONTACT` (who a waiting task waits on) and `ATTACH` `mid:` (the mail a capture came from) stay, shown and written, deciding no view.
- `X-PIMALAYA-GTD` is neither read nor written. A writer keeps every other category in order and edits only the three.

### The Tasks tab

Inbox, Next, Waiting and Someday, global across accounts and collections, plus a category filter over all of them (contexts, projects, anything). Projects and Reference leave the tab: a project is a filter, a reference is a deleted task or a note kept elsewhere.

### Capture

Unchanged in its entry points (the tab's button, the capture widget, the launcher shortcut, the share sheet, Add to tasks on a mail). It writes a VTODO with `CATEGORIES:inbox`. The dialog's target is a dropdown of the task collections, each named with its account, the last used preselected; hidden when there is one; with none, the dialog says why.

### Clarify

The guided flow stays, its result an edit of the same item: rephrase the title; not actionable: delete or Someday (`someday`); under two minutes: done; someone else: Waiting (`waiting`, and optionally the person as `CONTACT`); otherwise a next action, with categories (contexts, a project), a due date and optionally another collection. Moving to another collection is the only case that is not an edit: the task is created in the target, and removed from the source once the create is accepted, so a refused create never loses the capture.

### Elsewhere

- "Send and wait for a reply" creates a task with `waiting`, the recipient as `CONTACT` and the sent message by `mid:`.
- The weekly review lists Inbox, the calendar, Next, Waiting and Someday; "project with no next action" leaves it.
- The Next widget lists Next.

## Out

- Time tracking: unfog's for now; the app may later read the time entries pimdir's guide describes.
- Google Tasks and Microsoft To Do: unchanged, Later. Both carry categories or the like (To Do's `categories`; Google Tasks none), so the convention maps to To Do at least.
