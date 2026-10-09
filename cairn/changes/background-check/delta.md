---
cairn: delta
change: background-check
---

Folds into `spec/offline-store.md` (the job) and `spec/mail.md` (the notifications).

## ADDED Requirements

### Requirement: Accounts sync in the background
An account that is on SHALL sync its contacts, mail and calendars from a periodic job at the interval its settings pick (off, 15 minutes by default, 30 minutes, an hour, 2 hours), counted from its last sync of any kind, as Android schedules it, while the app is not on screen. A run SHALL skip an account owing a first sync of a domain, and SHALL NOT run while an in-app sync does; an in-app sync SHALL NOT start while a run does.

#### Scenario: Switched off
- GIVEN every account's interval is off
- WHEN the app is closed
- THEN no job is scheduled

### Requirement: New mail notifies
A run SHALL notify, for an account with new-mail notifications on, each unread inbox message its mail pass added, at most 5 per account per run, grouped under the account. Messages added otherwise SHALL NOT notify.

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

## MODIFIED Requirements

## REMOVED Requirements
