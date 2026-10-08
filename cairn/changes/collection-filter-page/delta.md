---
cairn: delta
change: collection-filter-page
---

## ADDED Requirements

### Requirement: The filter lists accounts and their collections
The filter SHALL be a page per domain listing each account with a checkbox and its collections below it with theirs, keyed by collection id and kept across restarts. An account's checkbox SHALL tick or untick all its collections and read partly ticked when some are; unticking an account SHALL keep its collections' own choices.

#### Scenario: Two accounts with an Archive
- GIVEN two mail accounts each holding a mailbox named Archive
- WHEN one account's Archive is unticked
- THEN the other account's Archive still shows

### Requirement: A role gives direct access across accounts
The mail list SHALL offer role chips (Inbox, Sent, Drafts, Trash, Junk, Archive, All), each narrowing the shown collections to those holding that role. Contacts and calendars SHALL offer a Default chip.

#### Scenario: Every trash
- GIVEN three shown mail accounts
- WHEN the Trash chip is chosen
- THEN the list shows the three trashes merged

### Requirement: A new contact or event goes to the default collection
A collection SHALL be its account's default when its source states it (JMAP `isDefault`, Graph's default calendar and contacts folder, Google's primary calendar and `myContacts`), else when it is the account's only writable one of its kind, else when the user set it so. A new contact or event SHALL go to the default; only an account with none SHALL ask, offering writable collections only.

#### Scenario: A Google account
- GIVEN a Google account with several calendars
- WHEN an event is created
- THEN it goes to the primary calendar without asking

## MODIFIED Requirements

## REMOVED Requirements
