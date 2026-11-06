---
cairn: delta
change: phone-deletions-held
---

# Delta

Folds into spec/carddav-sync.md (the books) and spec/calendar.md (the calendars).

## ADDED Requirements

### Requirement: A book wiped on the phone waits for the user
A phone pass finding more than half of at least 20 of a book's members deleted on the phone SHALL push none of those deletions and SHALL leave their rows deleted on the phone. When the app's sync ends, it SHALL ask whether to delete them on the server, put them back on the phone, or decide later. A deleted row SHALL be erased only after the store recorded its deletion.

#### Scenario: Another app wipes the contacts
- GIVEN a book of 500 contacts mirrored on the phone
- WHEN another app deletes all 500 raw contacts
- THEN the next pass deletes nothing on the server, and the app asks what to do with the 500

#### Scenario: A few deletions
- GIVEN a book of 500 contacts mirrored on the phone
- WHEN the user deletes 3 of them in the Contacts app
- THEN the next pass deletes the 3 on the server without asking

### Requirement: A calendar wiped on the phone waits for the user
A phone pass finding more than half of at least 20 of a calendar's entries deleted on the phone SHALL push none of those deletions and SHALL leave their rows deleted on the phone, asking as for a book. Putting them back SHALL project them again from the store.

#### Scenario: Another app empties a calendar
- GIVEN a calendar of 20 entries mirrored on the phone
- WHEN another app deletes all 20
- THEN the server keeps the 20, and putting them back shows the 20 again in the phone's calendar app

## MODIFIED Requirements

## REMOVED Requirements
