---
cairn: delta
change: task-filter-rows
---

Folds into spec/tasks.md.

## ADDED Requirements

## MODIFIED Requirements

### Requirement: The Tasks chips are filters
The Tasks tab SHALL carry two chip rows. The status row SHALL be In progress, Done and Cancelled, at most one on, none on meaning the open tasks, In progress counting its tasks. The category row SHALL begin with Inbox, Waiting and Someday, at most one on, each counting its tasks under the status on, none under Done or Cancelled, then hold the person's categories (A category filter narrows every list). Each chip SHALL be drawn as the mail tab's filter chips are. With no status on, Inbox, Waiting and Someday SHALL each keep the open tasks carrying that reserved value among their categories, compared folded, and none on SHALL be the next actions; under a status, none on SHALL be its tasks whatever their state, and one on SHALL narrow them to the tasks carrying it. Clarify and Weekly review SHALL be buttons of the Tasks list's app bar, not chips: Clarify shown while the Inbox holds an open item, Weekly review always.

#### Scenario: Turning a filter off
- GIVEN the Waiting chip on
- WHEN it is tapped again
- THEN no chip is on and the tab lists the next actions

#### Scenario: Clarify from the bar
- GIVEN an Inbox holding two items and no chip on
- WHEN the bar's Clarify button is tapped
- THEN the clarify flow opens on the first of the two

#### Scenario: A status within a list
- GIVEN a Waiting item set in progress and a next action set in progress
- WHEN In progress is on, then Waiting too
- THEN In progress lists both, and with Waiting on, the Waiting item alone

### Requirement: A task's STATUS is a view
In progress SHALL list the open tasks carrying `STATUS:IN-PROCESS` (RFC 5545 3.8.1.11), and every other list SHALL mark such a task In progress; the task page SHALL set it, writing `STATUS:IN-PROCESS`, and clear it, writing `STATUS:NEEDS-ACTION`. Done SHALL list the `COMPLETED` tasks and Cancelled the `CANCELLED` ones; a row's tick there SHALL reopen the task, writing `STATUS:NEEDS-ACTION` and clearing `COMPLETED` and `PERCENT-COMPLETE`, its categories kept. A new task SHALL carry no `STATUS`, a task carrying none reading as `NEEDS-ACTION`.

#### Scenario: A task ticked by mistake
- GIVEN a Waiting item
- WHEN its row's tick is tapped, then Done is on and its tick tapped again
- THEN it carries `STATUS:NEEDS-ACTION` and no `COMPLETED`, and the Waiting chip lists it

#### Scenario: Under way
- GIVEN a next action set in progress from its page
- WHEN In progress is on
- THEN the tab lists it, and with no chip on its row says In progress

### Requirement: A category filter narrows every list
The category row SHALL offer, after Inbox, Waiting and Someday, the categories the tasks of the status on carry (the open tasks with none on), the three reserved values aside, then any category on that they lack, one chip for spellings that fold alike (Unicode lowercase, locale-independent, trimmed), any number on, none on meaning every task, with no chip standing for all. The categories on SHALL narrow every list alike, the counts included, a task staying when it carries any of them, compared folded, and SHALL stay while the person switches chips. A task SHALL take part in the filter by any of its categories: contexts, projects, anything. Which calendars show SHALL be the bar's filter page's, as for the agenda.

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

#### Scenario: A category of an Inbox item
- GIVEN an Inbox item carrying `CATEGORIES:test` and next actions carrying no category
- WHEN the Tasks tab opens with no chip on
- THEN the category row offers Inbox, Waiting, Someday and test

## REMOVED Requirements
