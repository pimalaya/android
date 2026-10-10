---
cairn: delta
change: phone-contacts-mirror
---

Folds into a new `spec/phone-mirror.md`, which the calendar mirror joins.

## ADDED Requirements

### Requirement: Showing contacts on the phone is chosen
Both setups SHALL offer, for an account covering contacts, a switch "Show in phone contacts", off by default, asking the contacts permission when it is turned on; a refusal SHALL turn it back off. The permission SHALL NOT be asked elsewhere than there and on a book's settings option. (Revised 2026-10-10 by `onboarding-options-ask-on-switch`, which restates this requirement.)

#### Scenario: Refused
- GIVEN the standard setup with Contacts ticked
- WHEN the switch is turned on and the permission refused
- THEN the switch is off, and the account is connected with no book shown on the phone

### Requirement: A phone already showing the account is left alone
A setup SHALL NOT mirror an account's books when the phone's contacts already hold raw contacts of the same address under another account type, and SHALL say so.

#### Scenario: Google already syncs it
- GIVEN a phone signed in to alex@gmail.com with Google's contacts sync on
- WHEN alex@gmail.com is set up with the switch on
- THEN no book is mirrored, and the result page says the contacts are already on the phone through another app

### Requirement: The phone's contacts follow without a sync
A write to a mirrored book SHALL reach the phone's contacts within seconds, with no network; an edit made in the Contacts app SHALL reach the store without opening the app, and at once when the app comes back to the foreground. Neither SHALL be reported as a sync.

#### Scenario: Edited in the Contacts app
- GIVEN a mirrored contact, Pimalaya closed
- WHEN its phone number is changed in the Contacts app and Pimalaya is opened
- THEN Pimalaya shows the new number, and the next sync sends it to the server

#### Scenario: Edited in Pimalaya
- GIVEN a mirrored contact
- WHEN it is edited in Pimalaya and the Contacts app is opened
- THEN the Contacts app shows the edit, with no sync run

## MODIFIED Requirements

## REMOVED Requirements
