---
cairn: delta
change: narrow-google-calendar-scope
---

# Delta

## ADDED Requirements

### Requirement: Google's calendars are asked as their events and their list
The app's Google client SHALL ask, for calendars, `https://www.googleapis.com/auth/calendar.events` and `https://www.googleapis.com/auth/calendar.calendarlist.readonly`, never the full `https://www.googleapis.com/auth/calendar` scope: where io-pim-discovery's provider rule or a grant from an earlier release names the full one, the grant SHALL ask for the two in its place. A calendar SHALL sign in only when both come back from Google's consent page. The check that a grant signed in as the account's address SHALL read the primary calendar's id off the calendar list (`calendarList.get("primary")`), under the two or the full scope, so a grant from an earlier release keeps syncing and is still checked.

#### Scenario: The calendar list unticked on Google's consent page
- GIVEN a Google calendar signing in again
- WHEN the consent page grants its events and the tasks but not the calendar list
- THEN the calendar does not land, its page says Calendar was not allowed, and the account keeps its earlier sign-in

#### Scenario: A calendar granted by an earlier release
- GIVEN a Google calendar whose grant holds the full calendar scope
- WHEN it syncs, then signs in again
- THEN it syncs on that grant, and the new sign-in asks for its events and the calendar list

## MODIFIED Requirements

## REMOVED Requirements
