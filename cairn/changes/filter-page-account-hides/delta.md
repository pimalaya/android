---
cairn: delta
change: filter-page-account-hides
---

Folds into `spec/offline-store.md`.

## ADDED Requirements

## MODIFIED Requirements

### Requirement: The filter lists accounts and their collections
The filter SHALL be a page per domain listing each account that is on and covers the domain with a checkbox, and its collections indented below it with theirs: the mailboxes and the account's outbox, the calendars, or the subscribed address books, the on-device book listed as an account of its own. It SHALL be keyed by collection id and kept across restarts, per domain, a store with no kept filter showing everything. Unticking an account SHALL hide it from the lists and its collections from the page, each collection keeping its own choice; ticking it back SHALL list them again with their choices as they were. An account's checkbox SHALL read unticked when the account is hidden, ticked when it shows and all its collections do, partly ticked when it shows and some or none do. A collection's checkbox SHALL read its own choice. A Reset SHALL show everything again. Every tick SHALL apply to the list at once. The boxes SHALL be checkboxes, not switches, to stay apart from an account's on and off, and SHALL press with the theme's ripple.

#### Scenario: Two accounts with an Archive
- GIVEN two mail accounts each holding a mailbox named Archive
- WHEN one account's Archive is unticked
- THEN the other account's Archive still shows

#### Scenario: An account unticked and back
- GIVEN an account with its Archive unticked
- WHEN the account is unticked
- THEN its mail leaves the list and its mailboxes leave the page
- WHEN it is ticked again
- THEN its mailboxes are listed again, Archive alone unticked, and its mail shows without its Archive

#### Scenario: An account with nothing ticked
- GIVEN an account shown with every collection unticked
- WHEN the filter page is opened
- THEN its box reads partly ticked and its collections are listed unticked

#### Scenario: After a restart
- GIVEN a calendar unticked
- WHEN the app is restarted
- THEN the calendar is still hidden, and nothing is hidden in mail or contacts

## REMOVED Requirements
