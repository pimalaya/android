---
cairn: delta
change: preserve-account-settings-scroll
---

## ADDED Requirements

### Requirement: Returning from sign-in retains the settings position
Returning from Sign in again SHALL retain the settings scroll position and the corresponding toolbar title state from before Sign in again, while refreshing the account's displayed settings. Opening account settings normally SHALL still start at the top.

#### Scenario: Returning to the selected settings position
- GIVEN account settings scrolled to Sign in again
- WHEN the user selects it and returns from the flow
- THEN account settings are displayed at their previous scroll position
- AND the toolbar title matches that position
- WHEN the user leaves settings and opens them normally
- THEN the settings start at the top

## MODIFIED Requirements

None.

## REMOVED Requirements

None.
