---
cairn: delta
change: task-status-views
---

Folds into spec/tasks.md.

## ADDED Requirements

### Requirement: A task's STATUS is a view
In progress SHALL list the open tasks carrying `STATUS:IN-PROCESS` (RFC 5545 3.8.1.11), whatever their state, and every other open list SHALL mark such a task In progress; the task page SHALL set it, writing `STATUS:IN-PROCESS`, and clear it, writing `STATUS:NEEDS-ACTION`. Done SHALL list the `COMPLETED` tasks and Cancelled the `CANCELLED` ones, their state ignored, neither counted on its chip; a row's tick there SHALL reopen the task, writing `STATUS:NEEDS-ACTION` and clearing `COMPLETED` and `PERCENT-COMPLETE`, its categories kept. A new task SHALL carry no `STATUS`, a task carrying none reading as `NEEDS-ACTION`.

#### Scenario: A task ticked by mistake
- GIVEN a Waiting item
- WHEN its row's tick is tapped, then Done is on and its tick tapped again
- THEN it carries `STATUS:NEEDS-ACTION` and no `COMPLETED`, and the Waiting chip lists it

#### Scenario: Under way
- GIVEN a next action set in progress from its page
- WHEN In progress is on
- THEN the tab lists it, and with no chip on its row says In progress

## MODIFIED Requirements

### Requirement: The Tasks chips are filters
The Tasks tab's first chip row SHALL be In progress, Inbox, Waiting, Someday, Done and Cancelled, at most one on, each drawn as the mail tab's filter chips are, the open lists' chips carrying their counts; Inbox, Waiting and Someday SHALL each keep the open tasks carrying that reserved value among their categories, compared folded, and with none on the tab SHALL show the next actions. Clarify and Weekly review SHALL be buttons of the Tasks list's app bar, not chips: Clarify shown while the Inbox holds an open item, Weekly review always.

#### Scenario: Turning a filter off
- GIVEN the Waiting chip on
- WHEN it is tapped again
- THEN no chip is on and the tab lists the next actions

#### Scenario: Clarify from the bar
- GIVEN an Inbox holding two items and no chip on
- WHEN the bar's Clarify button is tapped
- THEN the clarify flow opens on the first of the two

### Requirement: A task is a VTODO read the GTD way
The Tasks tab SHALL list the VTODOs of every calendar the filter shows, across accounts, as the next actions, or, under a chip, as Inbox, Waiting, Someday, In progress, Done or Cancelled. A task's state SHALL be three reserved `CATEGORIES` values, `inbox`, `waiting` and `someday` (pimdir GUIDE §17), compared folded (Unicode lowercase, locale-independent, trimmed) and shown under the app's labels in every locale; a task SHALL be in the list of each state it carries. An open task carrying none of the three SHALL be a next action, open being any `STATUS` but `COMPLETED` and `CANCELLED`, a task stating no `STATUS` included; a `COMPLETED` or `CANCELLED` task SHALL be out of every open list, listed under Done or Cancelled alone. `X-PIMALAYA-GTD` SHALL be neither read nor written. Every other `CATEGORIES` value SHALL be the person's own tag, shown on the task's row. A task whose `RELATED-TO` of type `PARENT` names another one in the same list SHALL show under it, and that relation SHALL decide nothing else; who it waits on SHALL be its `CONTACT`, and the mail it came from an `ATTACH` or `URL` holding a `mid:` URI (RFC 2392), both shown and deciding no list.

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

#### Scenario: A closed task
- GIVEN a VTODO carrying `CATEGORIES:waiting` and `STATUS:COMPLETED`
- WHEN the Waiting chip is on, then Done
- THEN Waiting does not list it and Done does

### Requirement: A category filter narrows every list
The Tasks tab SHALL offer, under its chips, a row over the categories the tasks of the list shown carry, the three reserved values aside, one chip for spellings that fold alike (Unicode lowercase, locale-independent, trimmed), any number on, none on meaning every task, with no chip standing for all. The categories on SHALL narrow the next actions and every chip's list alike, the counts included, a task staying when it carries any of them, compared folded, and SHALL stay while the person switches chips. A task SHALL take part in the filter by any of its categories: contexts, projects, anything. Which calendars show SHALL be the bar's filter page's, as for the agenda.

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
The next actions and In progress SHALL list the soonest `DUE` first, as an instant, a task stating none after every one that does, then by `PRIORITY`, 1 first and 9 last, 0 or none after 9, then the oldest captured first, sectioned Due and No due date; Inbox, Waiting and Someday SHALL list the oldest captured first (pimdir GUIDE §17); Done SHALL list the most recently `COMPLETED` first, one stating none after, and Cancelled the most recently captured first. Captured is `CREATED`, else `DTSTAMP`. A subtask SHALL still show under its parent in the same list.

#### Scenario: Two next actions due alike
- GIVEN two next actions due the same day at the same time, one of `PRIORITY:1` captured last week and one of no priority captured last month
- WHEN the Tasks tab opens with no chip on
- THEN the one of `PRIORITY:1` comes first

#### Scenario: Two equal next actions
- GIVEN two next actions with no due date and no priority, one captured last month and one yesterday
- WHEN the Tasks tab opens with no chip on
- THEN last month's comes first

#### Scenario: Done
- GIVEN a task completed yesterday and one completed today
- WHEN Done is on
- THEN today's comes first

## REMOVED Requirements
