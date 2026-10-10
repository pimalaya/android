---
cairn: delta
change: onboarding-options-ask-on-switch
---

Folds into `spec/phone-mirror.md` (after `phone-contacts-mirror` and `phone-calendar-mirror` create it), `spec/onboarding.md` and `spec/mail.md` (after `background-check`).

## ADDED Requirements

### Requirement: A permission is asked only as its option is turned on
The app SHALL ask the contacts, calendar and notifications permissions only when the user turns on an option needing them: a phone switch or the notifications switch of a setup, or the matching option in an account's settings. A refusal SHALL turn that option back off. Nothing else SHALL prompt: not continuing a setup, not opening the app, not a sync. An option whose permission was revoked in the system settings SHALL read as off, and turning it on SHALL ask again.

#### Scenario: Refused in a setup
- GIVEN the standard setup with Contacts ticked
- WHEN "Show in phone contacts" is turned on and the permission refused
- THEN the switch is off again, and Continue asks nothing

#### Scenario: Revoked later
- GIVEN an account notifying new mail, its notifications permission then revoked in the system settings
- WHEN its settings are opened
- THEN "Notify new mail" reads off, and turning it on asks the permission

### Requirement: The setups offer new-mail notifications
Both setups SHALL offer, for an account covering mail, a switch "Notify new mail", off by default, asking the notifications permission on Android 13 and later when it is turned on. Connected with it on, the account SHALL notify new mail and SHALL sync in the background, at the default interval when its interval was off.

#### Scenario: Turned on
- GIVEN the standard setup with Mail ticked
- WHEN "Notify new mail" is turned on, the permission granted, and the setup connects
- THEN the account's settings show "Notify new mail" on and background sync every 15 minutes

#### Scenario: Left off
- GIVEN the same setup with the switch left off
- WHEN it connects
- THEN no permission was asked and the account does not notify

## MODIFIED Requirements

### Requirement: Showing contacts on the phone is chosen
Both setups SHALL offer, for an account covering contacts, a switch "Show in phone contacts", off by default, asking the contacts permission when it is turned on; a refusal SHALL turn it back off. The calendars SHALL be offered the same way, "Show in phone calendar", asking the calendar permission. Turning a book on in an account's settings SHALL bring it to the phone only when the account's other books are there and the permission is held, and SHALL NOT prompt.

#### Scenario: Refused
- GIVEN the standard setup with Contacts ticked
- WHEN the switch is turned on and the permission refused
- THEN the switch is off, and the account is connected with no book shown on the phone

### Requirement: New mail notifies
A run SHALL notify, for an account with new-mail notifications on, each unread inbox message its mail pass added, at most 5 per account per run, grouped under the account. Messages added otherwise SHALL NOT notify. An account SHALL NOT notify until turned on, in a setup or in its settings.

#### Scenario: Two messages arrive
- GIVEN an account with notifications on and an inbox the last run left unchanged
- WHEN two messages arrive and a run syncs
- THEN two notifications show under the account's summary

#### Scenario: Widening the bound
- GIVEN an account whose bound is widened in the app
- WHEN the older messages are listed
- THEN nothing notifies

#### Scenario: Pulling during a run
- GIVEN a background run syncing when the app is opened
- WHEN the list is pulled
- THEN the sync is turned down with "A background sync is running", and the strip says one is

## REMOVED Requirements
