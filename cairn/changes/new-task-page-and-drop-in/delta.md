---
cairn: delta
change: new-task-page-and-drop-in
---

Folds into `spec/tasks.md` (the add button) and `spec/offline-store.md` (transitions).

## ADDED Requirements

### Requirement: A new task is written on the task page
The Tasks tab's add button SHALL read New task and open the task page empty, with the sections and dialogs of an edit and its calendar a picker among the calendars taking tasks, the one the last task went to first. Saving SHALL stage one create, asking for a title first when there is none.

#### Scenario: A new task with a due date
- GIVEN the Tasks tab
- WHEN New task is pressed, a title and a due date set and the page saved
- THEN a dated task is staged in the calendar shown on the page

### Requirement: Pages drop in
A page opened from a list SHALL drop in as an account's settings do; leaving a page or an overlay SHALL NOT be animated, and no screen SHALL slide in or out, the drawer aside. A page creating or editing an item SHALL show a cross in place of the back arrow.

#### Scenario: Opening a contact
- GIVEN the contacts list
- WHEN a contact is opened
- THEN its page drops in, a cross at the bar's start

## MODIFIED Requirements

### Requirement: A capture is on the phone at once
As it stands, but the Tasks tab's add button SHALL open the task page (A new task is written on the task page), not the sheet, and the capture widget and the launcher shortcut SHALL open it with Inbox set; the share sheet and a mail's Add to tasks SHALL still open the sheet.

### Requirement: A task opens on its page
As it stands, but the page SHALL read, in order: its calendar; Organise, always shown (Categories, one row holding the GTD list as one choice among Inbox, Waiting and Someday and the categories as any number; who a waiting task waits on; the task it sits under); Details (the title, the notes, the priority, the due date); Progress (the status); the mail it came from. There SHALL be no When section.

### Requirement: The entry page reads Calendar first
As it stands (spec/calendar.md, from event-calendar-dropdown), but Calendar SHALL be one row naming the calendar and its account, a tap opening a picker while a new entry may go to several calendars, in place of the dropdown, as the task page's. The scenario reading "a dropdown in a card on top" SHALL read "a row on top, a tap offering each calendar with its account", and with one writable calendar the row SHALL take no tap.

### Requirement: The contact page reads its address book first
The contact page SHALL open on an Address book section naming the book and its account, a tap picking the book of a new contact among the subscribed ones, or placing a stored one in books as the books dialog does; the bar SHALL carry no books button.

## REMOVED Requirements
