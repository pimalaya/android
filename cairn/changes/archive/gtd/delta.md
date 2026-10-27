---
cairn: delta
change: gtd
---

Folds into a new `spec/tasks.md`, and into `spec/calendar.md` and `spec/mail.md` where marked.

## ADDED Requirements

### Requirement: A task is a VTODO read the GTD way
The Tasks tab SHALL list the VTODOs of the calendars the filter shows, each in the list its `X-PIMALAYA-GTD` names (`INBOX`, `WAITING`, `SOMEDAY`, `PROJECT`, `REFERENCE`). A VTODO carrying none, or one this side does not know, SHALL be a next action. Contexts SHALL be its `CATEGORIES`, its project the `UID` a `RELATED-TO` of type `PARENT` names, who it waits on its `CONTACT`, and the mail it came from an `ATTACH` or `URL` holding a `mid:` URI (RFC 2392). A `COMPLETED` or `CANCELLED` task SHALL be out of every list.

#### Scenario: A task another client created
- GIVEN a CalDAV calendar holding a VTODO with no `X-PIMALAYA-GTD`
- WHEN the Tasks tab loads
- THEN it lists the VTODO under Next

### Requirement: A task write patches the object
A task write SHALL patch the stored VTODO through its syntax tree, every property it does not set left byte for byte, and stage the result for the calendar's next sync. Clearing the project SHALL remove only the `RELATED-TO` of type `PARENT`, and clearing the source only the `ATTACH` holding a `mid:` URI.

#### Scenario: Another client's relation
- GIVEN a VTODO relating a sibling and attaching a document
- WHEN its project and its source are cleared
- THEN the sibling relation and the document attachment stay

### Requirement: A capture is on the phone at once
A capture SHALL be staged at once as a dateless VTODO in the Inbox of a writable CalDAV calendar, offline included, from the Tasks tab, a launcher shortcut, the capture widget, the share sheet, or a mail's Add to tasks. A capture from a mail SHALL link it by `mid:` and leave the mail where it is filed.

#### Scenario: Capturing a mail
- GIVEN a mail in INBOX
- WHEN Add to tasks is saved from the reader
- THEN the Inbox holds a task linked to the mail's `Message-ID` and the mail is still in INBOX

### Requirement: Only CalDAV calendars take tasks
A capture SHALL be offered only writable calendars served over CalDAV; a Microsoft, Google or JMAP calendar and the phone's local calendar SHALL NOT be offered.

#### Scenario: No CalDAV calendar
- GIVEN accounts whose calendars are all Graph or Google
- WHEN Capture is pressed
- THEN the app says no calendar takes tasks yet

### Requirement: Clarifying never puts an item back
The clarify flow SHALL ask, one Inbox item at a time, whether it is actionable, under two minutes, someone else's, more than one step, then its next action, and write the outcome as one change. A skipped item SHALL stay in the Inbox, first in line for the next pass.

#### Scenario: A capture that is a project
- GIVEN an Inbox item
- WHEN it is clarified as actionable, longer than two minutes, mine, more than one step, with a next action
- THEN the item becomes a project and a new next action names it as its parent

### Requirement: A reply marks a Waiting For
A Waiting For item linked to a mail SHALL show a reply received when the store holds a mail whose `In-Reply-To` names that mail. That SHALL be computed at load, never written.

#### Scenario: Send and wait
- GIVEN a mail sent with Send and wait for a reply
- WHEN a reply to it is synced
- THEN its Waiting For item shows Reply received

### Requirement: The weekly review counts, it does not guess
The weekly review SHALL list the Inbox left, the calendar, the open next actions, the Waiting For items with the replies received and those over two weeks, the projects with no next action, and Someday, each opening what it counts. Finishing it SHALL keep the day on the device, and the Tasks header SHALL say when one is due again, seven days after.

#### Scenario: A project with no next action
- GIVEN a project whose actions are all done
- WHEN the weekly review opens
- THEN its Projects step counts one project with no next action

### Requirement: Captures go to the calendar last picked
A capture SHALL go to the calendar last picked, in the capture sheet or in account settings, kept on the device; with none picked, or the picked one gone, to a default calendar taking tasks, else the first.

#### Scenario: Picked in account settings
- GIVEN an account with two CalDAV calendars, Work the default
- WHEN Home is tapped in the account's Tasks card
- THEN the next capture sheet names Home

### Requirement: An account says whether it takes tasks
Account settings SHALL carry a Tasks card for an account holding calendars: each calendar taking tasks, the one captures go to marked, and each read-only one as events only. An account none of whose calendars take tasks SHALL get the line saying so, naming Microsoft's or Google's own service when it is theirs. Onboarding SHALL say it in one line under a calendar option that is not CalDAV.

#### Scenario: A Microsoft account
- GIVEN an account whose calendars run over Graph
- WHEN its settings open
- THEN the Tasks card says tasks are not available there, Microsoft keeping them in its own service

### Requirement: The Next widget lists the next actions
The Next widget SHALL list the open next actions the Tasks tab last read, due ones first, a row opening its task page and the header the Next list. It SHALL read no store to draw.

#### Scenario: A row
- GIVEN the Next widget on the home screen
- WHEN a row is tapped
- THEN the app opens on that task's page

### Requirement: An agenda to-do opens on the task page (spec/calendar.md)
A dated to-do tapped in the agenda SHALL open on the task page, which goes back to the agenda. A conflicted one, or one that does not read as a task, SHALL keep the entry page.

#### Scenario: A due to-do
- GIVEN a to-do due on Friday
- WHEN its row is tapped in Friday's agenda
- THEN the task page opens, and back returns to the agenda

### Requirement: The composer sends and waits for a reply (spec/mail.md)
A long press on Send SHALL offer Send and Send and wait for a reply; the latter SHALL queue the mail as Send does and file a Waiting For item on its first recipient, linked to the `Message-ID` it was queued under.

#### Scenario: Waiting on a recipient
- GIVEN a mail to Léa Martin
- WHEN it is sent with Send and wait for a reply
- THEN Waiting For holds its subject, waiting on Léa Martin, linked to the sent mail

## MODIFIED Requirements

## REMOVED Requirements
