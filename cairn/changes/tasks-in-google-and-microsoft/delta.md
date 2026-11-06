---
cairn: delta
change: tasks-in-google-and-microsoft
---

Folds into `spec/tasks.md`, after account-settings-page's delta.

## ADDED Requirements

### Requirement: Google and Microsoft task lists hold tasks
An account signed in to Google's or Microsoft's own APIs SHALL list its task lists (Google Tasks, Microsoft To Do) beside its calendars, each a collection taking tasks alone, never an event's target nor a calendar of the phone's. A To Do task SHALL carry Waiting and Someday as To Do's statuses while it is open and states no other, every other line Graph has no field for kept server-side. A Google task SHALL keep on the phone alone what Google has no field for (its state, categories, who it waits on, the mail it came from), laid back over each read, and the task page, the capture sheet and the account settings SHALL say so for a Google list. A token granted without the task service SHALL list the calendars alone.

#### Scenario: Waiting on To Do
- GIVEN a To Do list
- WHEN a task there is set to Waiting on the phone and synced
- THEN To Do shows it waiting on others, and the phone reads it back as Waiting

#### Scenario: A state on a Google list
- GIVEN a Google task list and a task there set to Someday on the phone
- WHEN its title is edited in Google's own app and the list syncs
- THEN the phone shows the new title and the task still in Someday

## MODIFIED Requirements

### Requirement: Only CalDAV calendars take tasks
A capture SHALL be offered only writable calendars served over CalDAV for which no source syncing them states `x-vtodo` `none` (pimdir STORAGE §15.6), and writable To Do and Google task lists; a Microsoft, Google or JMAP calendar and the phone's local calendar SHALL NOT be offered. A CalDAV listing SHALL declare `x-vtodo` `none` for a calendar whose `supported-calendar-component-set` names components but not `VTODO`, and `full` otherwise, and SHALL record the server source as syncing each calendar it lists, a calendar's sync state kept as it stands (none before its first round), so the declaration counts from the listing on, before the calendar's first sync. A create or a move of a task into a calendar not taking tasks SHALL be refused before anything is staged.

#### Scenario: No list takes tasks
- GIVEN accounts whose calendars are all JMAP
- WHEN Capture is pressed
- THEN the capture sheet says no list takes tasks yet and offers no save

#### Scenario: An events-only CalDAV calendar
- GIVEN a CalDAV calendar whose `supported-calendar-component-set` names `VEVENT` alone, listed and never synced
- WHEN Capture is pressed
- THEN the capture sheet does not offer it

### Requirement: An account says whether it takes tasks
Account settings SHALL carry a *Tasks* row in the Calendar section for an account holding calendars: the calendars and task lists taking tasks ("In Work, Home"), for a Google account adding that states and categories stay on the phone; or the line saying none does, which for a Google or Microsoft account tells that adding the account again brings its task lists. Onboarding SHALL say it in one line under a calendar option that is neither CalDAV nor Google's nor Microsoft's.

#### Scenario: A Microsoft account added before
- GIVEN an account whose Graph token was granted without To Do
- WHEN its settings open
- THEN the Tasks row says its To Do lists sync once the account is added again

## REMOVED Requirements
