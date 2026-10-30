---
cairn: change
id: event-page-sections
status: landed
created: 2026-10-30
---

# The entry page in four sections; one form per alarm; clearable dates

## Why

The user's device round of 2026-10-30, after event-alarm-details: an alarm was edited through a chain of dialogs where every other field opens one, so a new alarm could not take a message; Progress and Where split Details; When carried an add button though its rows are what an entry is; the bar's add button duplicated the sections' own; an optional date could not be cleared; the calendar dropdown's padding was tighter than a row's.

## What

- **Four sections, always shown**: Calendar (the dropdown while creating with several calendars, else the calendar and its account), When, Details, Alarms; then people, conflicts and links where the entry has them. The bar's add button goes; Details and Alarms keep their headers' add buttons.
- **When** shows all-day, the start and the end (an event) or due date (a to-do) always, with no add button; an optional date is cleared from its date picker's Clear button: every date but an event's start, which RFC 5545 3.6.1 requires.
- **Details** holds the title, always, then the description, location, status, priority, progress, completion, categories and URL once set or added.
- **One alarm form**: type, time, message, and an email's subject and recipients in one dialog, for a new alarm as for an existing one (with Remove); a time no preset names is offered first as it stands.
- **The calendar dropdown** takes a row's padding.
