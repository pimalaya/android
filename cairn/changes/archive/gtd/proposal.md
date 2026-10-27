---
cairn: change
id: gtd
status: landed
created: 2026-10-27
---

# Tasks, organised the GTD way

## Why

The app stores to-dos and never shows them as tasks. A CalDAV calendar's VTODOs are synced with its events (spec/calendar.md: the enumeration takes the whole collection), placed on a day by their `DUE` and edited on the entry page like an event, but there is no list of them, no way to capture one in a second, and nothing that helps a person keep a trusted system. GTD (Getting Things Done, David Allen) fails in practice for lack of rigour, not of features: captures pile up unprocessed, projects lose their next action, Waiting For goes stale. The app already holds what those steps need (the mail a task comes from, the thread a reply arrives in, the calendar), so it can carry the method rather than leave it to the person's memory.

The design is AI-ready but carries no AI: every step a person takes by hand produces the same structured record a model would later pre-fill, so AI plugs in without reshaping the flow (see Later).

## Principles

- **Standards first.** A task is a VTODO (RFC 5545) and every GTD notion maps to one of its properties, or to one `X-` property where the standard has none. Another CalDAV client sees a sensible task and keeps what it does not understand (RFC 5545 §3.8.8.2).
- **pimdir is the source of truth.** The stored object is the task; nothing the app shows lives elsewhere.
- **Map only what the provider carries.** A field the account cannot hold is not stored on the side: the account says so (Capabilities).
- **GTD is opt-in by use.** A task with no bucket, context or project is a plain to-do; someone who never clarifies anything has a working task list.
- **The mail inbox is not the GTD inbox.** Many people keep everything in INBOX. Mail enters GTD only by an explicit capture, which links the message and leaves it where it is.

## What

### The model

A GTD item is a VTODO in a `text/calendar` collection whose server accepts VTODO.

| GTD | VTODO |
| --- | --- |
| Title, notes | `SUMMARY`, `DESCRIPTION` |
| Bucket: Inbox, Waiting For, Someday/Maybe | `X-PIMALAYA-GTD:INBOX`, `WAITING`, `SOMEDAY` |
| Next action | `STATUS:NEEDS-ACTION` (or `IN-PROCESS`) with no bucket |
| Done, dropped | `STATUS:COMPLETED` with `COMPLETED`, `STATUS:CANCELLED` |
| Project | a VTODO marked `X-PIMALAYA-GTD:PROJECT`; its actions name it by `RELATED-TO;RELTYPE=PARENT` |
| Context | `CATEGORIES`, each value a context |
| Due, start | `DUE`, `DTSTART` |
| Priority | `PRIORITY` |
| Waiting on whom | `CONTACT` (the name, `ALTREP` the `mailto:`) |
| The mail it came from | `ATTACH` with a `mid:` URI (RFC 2392), `URL` left to the person |

A task with no `X-PIMALAYA-GTD` is a next action: that is what a task created by another client is, and it keeps the plain to-do list working. A project is marked so that one with no actions yet still shows; one task naming another as `PARENT` without the marker is a subtask, shown under its parent but not as a project.

The fields are derived at read time in the app, from the VTODO bodies the calendar sync already stores, as the contacts list derives a contact's phone from its card: no pimdir or io-pimdir change is needed. Moving them into pimdir (summary columns, references from `RELATED-TO` and `mid:`, search fields) is Later.

### The Tasks tab

A fifth tab: Mail, Contacts, Calendar, Tasks, Files. GTD lives there and nowhere else; the app's entry point does not change.

- **Inbox**: unclarified captures, oldest first, with a count and the way into clarification.
- **Next**: next actions, filtered by context; due ones first.
- **Waiting**: what someone else owes, with whom and since when.
- **Projects**: each with its actions; one with no next action is marked.
- **Someday**: ideas kept for the weekly review.

A task opens on a task page that is the reading screen and the form at once, as an event's and a contact's are.

### Capture

One capture dialog, a title and an optional note, saved locally at once as an Inbox item (no network needed). It opens from:

- the Tasks tab's button;
- a home-screen widget (a widget takes no typed text, so it opens the dialog over the home screen);
- a launcher shortcut (long press on the icon);
- the Android share sheet, the shared text or link prefilled;
- a mail: "Add to tasks" in the reader's menu and the selection bar, the message linked by `mid:`, its subject prefilled.

A capture goes to the calendar last picked, in the dialog or in account settings, else the default one taking tasks; the dialog names it and lets the person change it.

### Clarify

A guided flow over the Inbox, one item at a time, each step a single question in GTD's order:

1. Is it actionable? No: delete, keep as reference (the note stays on the task, moved out of the lists), or Someday.
2. Less than two minutes? Do it now and mark it done.
3. Is someone else doing it? Waiting For, with whom.
4. More than one step? A project, with its first next action.
5. Otherwise: a next action, with its context, an optional due date and project.

The item never goes back to the Inbox: skipping it leaves it first in line for the next pass. Every step can show a prefilled answer, so the flow never assumes it starts empty. Applying the result is one write of the VTODO.

### Waiting For from the composer

The composer gains "Send and wait for a reply": the mail is sent and a Waiting For task is created, naming the recipient and linking the sent message by `mid:`. When a mail whose `In-Reply-To` or `References` names it arrives, the task shows "reply received" on its row and in the weekly review. That is computed from the store, never written, so it holds whatever client closed the task.

### The weekly review

A checklist screen, each item a query, nothing guessed:

1. Inbox: how many left, and the way into clarification.
2. The past week's calendar and the next two weeks'.
3. Next actions: done ones to clear.
4. Waiting For: oldest first, "reply received" ones marked, a reply to chase opening the composer on the thread.
5. Projects with no next action.
6. Someday: anything to activate.

The date it was last done is kept on the device, and the Tasks tab says when one is due.

### Calendar

The agenda keeps showing to-dos with a date, read-only in their own row style, and opening one opens the task page. Fixed commitments stay events; GTD's daily view is the agenda first, then Next.

### Capabilities

The first draft works on CalDAV calendars that accept VTODO (`supported-calendar-component-set`). An account without one (Google, Microsoft, today's JMAP calendars, whose writes are refused) has no task collection: account settings say so on a line of its own, at the point of use too (the capture dialog with no collection to pick), and onboarding mentions it in one line, never as its only place.

## Later

- **Google Tasks and Microsoft To Do**, each mapped to what it carries: Google keeps title, notes, a due date and done, with buckets as lists; Microsoft has `waitingOnOthers`, `deferred`, `categories` and `linkedResources`, but a task's id changes when it moves between lists, so buckets stay statuses there.
- **Export and import**: iCalendar VTODO with the `X-` properties, so a person can restore or move to a CalDAV server whole. To be decided.
- **AI**: a capture sent to a model returns the clarification record prefilled, accepted as is or edited in the flow; the weekly review gets a drafted chase for each stale Waiting For. The content leaving for a model is a per-account setting, stated before the first use.
- **pimdir**: `PRIORITY` and `CATEGORIES` in the task summary, references from `RELATED-TO` and `mid:` (STORAGE §14.2, which `linked:` already searches), `category:`, `status:` and `priority:` in SEARCH §8. Needs io-pimdir to follow; worth it when a second consumer (the CLI, MCP) reads tasks.

## Open questions

- **Waiting since**: `DTSTART` set when the item enters Waiting For, or an `X-` property of its own. `DTSTART` is standard but means "start" to other clients.
- **Due reminders**: phone-calendar-mirror leaves VTODO out of the phone's Calendar app, so nothing notifies a due task. Our own notifications, or a projection to a tasks provider (OpenTasks, as DAVx5 does, which Tasks.org and jtx Board read).
- **A phone-only task collection** for a person with no CalDAV account: whether the store can hold a collection no source binds.
- **The default collection** when several accounts accept tasks.
- **Reference**: whether a non-actionable item worth keeping stays a VTODO (`STATUS:CANCELLED` and a marker) or becomes a VJOURNAL.
