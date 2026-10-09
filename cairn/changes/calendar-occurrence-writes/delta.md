---
cairn: delta
change: calendar-occurrence-writes
---

Folds into `spec/calendar.md`.

## ADDED Requirements

### Requirement: One occurrence reaches every calendar backend
An occurrence edited or deleted alone SHALL reach Graph and Google calendars as a write of that instance, not only CalDAV ones as an override in the object.

#### Scenario: A moved Outlook meeting
- GIVEN a weekly series on a Microsoft account
- WHEN its Tuesday occurrence is moved to Wednesday with "This occurrence"
- THEN after the next sync Outlook shows that week's meeting on Wednesday and the others on Tuesday

## MODIFIED Requirements

## REMOVED Requirements
