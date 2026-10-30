---
cairn: delta
change: tasks-for-everyone
---

Folds into spec/tasks.md. The capability's lead paragraph becomes: Tasks are the VTODOs of the CalDAV calendars, listed as the open tasks of the calendars the filter shows in a Tasks tab, with the GTD lists (Getting Things Done) beside them as filters: an Inbox for captures, Waiting For and Someday across accounts, a category filter over all, a guided clarify flow and a weekly review. The state is three reserved `CATEGORIES` values, `inbox`, `waiting` and `someday`, as pimdir GUIDE §17 states them, so another client reads and sets it too; every other category is the person's own tag, and nothing but the body is written.

## ADDED Requirements

### Requirement: The Tasks chips are filters
The Tasks tab's first chip row SHALL be Inbox, Waiting and Someday, at most one on, each drawn as the mail tab's filter chips are, the Inbox chip carrying the Inbox count; a chip on SHALL keep the open tasks carrying that reserved value among their categories, compared folded, and with none on the tab SHALL show the next actions. Clarify and Weekly review SHALL be buttons of the Tasks list's app bar, not chips: Clarify shown while the Inbox holds an open item, Weekly review always.

#### Scenario: Turning a filter off
- GIVEN the Waiting chip on
- WHEN it is tapped again
- THEN no chip is on and the tab lists the next actions

#### Scenario: Clarify from the bar
- GIVEN an Inbox holding two items and no chip on
- WHEN the bar's Clarify button is tapped
- THEN the clarify flow opens on the first of the two

## MODIFIED Requirements

### Requirement: A task is a VTODO read the GTD way
The Tasks tab SHALL list the VTODOs of every calendar the filter shows, across accounts, as the next actions, or, under a chip, as Inbox, Waiting or Someday. A task's state SHALL be three reserved `CATEGORIES` values, `inbox`, `waiting` and `someday` (pimdir GUIDE §17), compared folded (Unicode lowercase, locale-independent, trimmed) and shown under the app's labels in every locale; a task SHALL be in the list of each state it carries. An open task carrying none of the three SHALL be a next action, open being any `STATUS` but `COMPLETED` and `CANCELLED`, a task stating no `STATUS` included; a `COMPLETED` or `CANCELLED` task SHALL be out of every list. `X-PIMALAYA-GTD` SHALL be neither read nor written. Every other `CATEGORIES` value SHALL be the person's own tag, shown on the task's row. A task whose `RELATED-TO` of type `PARENT` names another one in the same list SHALL show under it, and that relation SHALL decide nothing else; who it waits on SHALL be its `CONTACT`, and the mail it came from an `ATTACH` or `URL` holding a `mid:` URI (RFC 2392), both shown and deciding no list.

#### Scenario: A task another client created
- GIVEN a CalDAV calendar holding a VTODO with no `CATEGORIES` and no `STATUS`
- WHEN the Tasks tab loads with no chip on
- THEN it lists the VTODO

#### Scenario: A state another client set
- GIVEN a VTODO carrying `CATEGORIES:@home,Waiting`
- WHEN the Waiting chip is on
- THEN the tab lists the VTODO, with `@home` its one category

#### Scenario: The former property
- GIVEN a VTODO carrying `X-PIMALAYA-GTD:WAITING` and no reserved category
- WHEN the Tasks tab loads with no chip on
- THEN it lists the VTODO

### Requirement: A capture is on the phone at once
A new task SHALL be staged at once as a dateless VTODO, `CREATED` and `DTSTAMP` both the instant it was saved in UTC, in a calendar that takes tasks, offline included, from one task sheet carrying its calendar, three state chips, Inbox, Waiting and Someday, at most one on, and a categories field whose entries show as removable badges. The task SHALL carry the reserved value of the chip on, none when none is, and the categories typed, trimmed, folded repeats dropped, a reserved value among them refused, written as pimdir GUIDE §17 writes them: the categories a line, the state a line of its own after it, and no `CATEGORIES` when both are empty. The Tasks tab's add button, the share sheet and a mail's Add to tasks SHALL open the sheet with no chip on; the capture widget and the launcher shortcut SHALL open it with Inbox on. A task added from a mail SHALL link it by `mid:` and leave the mail where it is filed.

#### Scenario: Capturing from the widget
- GIVEN the capture widget on the home screen
- WHEN it is tapped and a title saved
- THEN the Inbox holds a task carrying `CATEGORIES:inbox` and `CREATED`

#### Scenario: Adding a task from a mail
- GIVEN a mail in INBOX
- WHEN Add to tasks is saved from the reader, nothing changed in the sheet
- THEN the next actions hold a task carrying no `CATEGORIES`, linked to the mail's `Message-ID`, and the mail is still in INBOX

#### Scenario: A state and categories
- GIVEN the task sheet opened from the add button
- WHEN Waiting is on, `Work` and `calls` typed as categories, and a title saved
- THEN the task carries `CATEGORIES:Work,calls`, then `CATEGORIES:waiting`

### Requirement: The weekly review counts, it does not guess
The weekly review SHALL list the Inbox left, the calendar, the open next actions, the Waiting For items with the replies received and those over two weeks, and Someday, each opening what it counts. Finishing it SHALL keep the day on the device, and the Tasks header SHALL say when one is due again, seven days after; before a first review is finished, the header SHALL say nothing of it.

#### Scenario: Waiting too long
- GIVEN a Waiting For item captured three weeks ago
- WHEN the weekly review opens
- THEN its Waiting For step counts one item over two weeks

#### Scenario: Never reviewed
- GIVEN a device where no weekly review was finished
- WHEN the Tasks tab loads
- THEN its header carries no review line

### Requirement: Captures go to the calendar last picked
The task sheet SHALL offer the task calendars as a dropdown, each named with its account, the one the last task went to preselected, else the first; the dropdown SHALL be hidden when one calendar takes tasks. There SHALL be no default calendar to set elsewhere.

#### Scenario: Picked in the sheet
- GIVEN two CalDAV calendars, Work and Home, and a task saved into Home
- WHEN the task sheet opens again
- THEN its dropdown has Home preselected

### Requirement: The Next widget lists the next actions
The Next widget SHALL list the open next actions the Tasks tab last read, due ones first, a row opening its task page and the header the Tasks tab with no chip on. It SHALL read no store to draw.

#### Scenario: A row
- GIVEN the Next widget on the home screen
- WHEN a row is tapped
- THEN the app opens on that task's page

### Requirement: A category filter narrows every list
The Tasks tab SHALL offer, under its chips, a row over the categories the open tasks carry, the three reserved values aside, one chip for spellings that fold alike (Unicode lowercase, locale-independent, trimmed), any number on, none on meaning every task, with no chip standing for all. The categories on SHALL narrow the next actions, Inbox, Waiting and Someday alike, their counts included, a task staying when it carries any of them, compared folded, and SHALL stay while the person switches chips. A task SHALL take part in the filter by any of its categories: contexts, projects, anything. Which calendars show SHALL be the bar's filter page's, as for the agenda.

#### Scenario: A category is a filter
- GIVEN two next actions, one carrying `CATEGORIES:Garden` and one carrying none
- WHEN Garden is on in the category row
- THEN the next actions list the first alone, and the Waiting and Someday chips count only Garden tasks

#### Scenario: Two categories on
- GIVEN next actions carrying `CATEGORIES:@home`, `CATEGORIES:@phone` and `CATEGORIES:Garden`
- WHEN @home and @phone are on
- THEN the next actions list the first two

#### Scenario: Two spellings
- GIVEN a next action carrying `CATEGORIES:Work` and another carrying `CATEGORIES:work`
- WHEN Work is on in the category row
- THEN the next actions list both, and the row offers Work once

#### Scenario: Nothing on
- GIVEN Garden on in the category row
- WHEN it is tapped again
- THEN the next actions list every task

### Requirement: Each list has one order
The next actions SHALL list the soonest `DUE` first, as an instant, a task stating none after every one that does, then by `PRIORITY`, 1 first and 9 last, 0 or none after 9, then the oldest captured first, sectioned Due and No due date; Inbox, Waiting and Someday SHALL list the oldest captured first (pimdir GUIDE §17). Captured is `CREATED`, else `DTSTAMP`. A subtask SHALL still show under its parent in the same list.

#### Scenario: Two next actions due alike
- GIVEN two next actions due the same day at the same time, one of `PRIORITY:1` captured last week and one of no priority captured last month
- WHEN the Tasks tab opens with no chip on
- THEN the one of `PRIORITY:1` comes first

#### Scenario: Two equal next actions
- GIVEN two next actions with no due date and no priority, one captured last month and one yesterday
- WHEN the Tasks tab opens with no chip on
- THEN last month's comes first

## REMOVED Requirements
