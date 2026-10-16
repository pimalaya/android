---
cairn: delta
change: phone-account-types
---

## ADDED Requirements

### Requirement: Calendar accounts stay out of contacts apps
The Android accounts holding the phone's calendars SHALL be of a type of their own, bound to the calendar adapter alone, so no contacts app lists them. Calendar accounts of the books' type left by an earlier build SHALL be migrated: their calendars' phone pass first, then the account removed and recreated under the calendar type.

#### Scenario: Picking where a new contact goes
- GIVEN an address whose books and calendars both show on the phone
- WHEN the Contacts app lists the accounts a contact can be saved into
- THEN it lists the books' accounts and no calendar account

### Requirement: Removing a phone account never drops a phone edit
An Android account SHALL be removed only after its phone pass brought every phone edit into the store. Deleting an app account SHALL run its books' phone pass first and SHALL stop, removing nothing, when one fails. A book's phone switch turned off SHALL keep its account when the last phone pass fails.

#### Scenario: Deleting an account with an unsynced Contacts-app edit
- GIVEN a contact added in the Contacts app under a book's account, not yet in the store
- WHEN the account is deleted in the app
- THEN the contact is brought into the store first and moves to the on-device book with the others

## MODIFIED Requirements

## REMOVED Requirements
