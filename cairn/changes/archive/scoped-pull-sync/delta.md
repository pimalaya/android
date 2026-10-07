---
cairn: delta
change: scoped-pull-sync
---

## ADDED Requirements

### Requirement: A list's pull syncs what it shows
Pulling a list down SHALL sync that list's domain alone, and within it only the accounts and collections the filter shows; the contacts pull SHALL also run the phone's pass. The drawer's sync SHALL take every domain, every account and every collection, whatever the filter hides.

#### Scenario: Pulling the agenda
- GIVEN two calendar accounts, one hidden by the filter
- WHEN the agenda is pulled down
- THEN only the shown account's calendars are synced, and no mail or contact is

## MODIFIED Requirements

### Requirement: The bottom bar switches domains
A bottom navigation bar SHALL switch between mail, contacts and calendars, in that order, the one on screen on a neutral indicator, mail carrying the count of unread messages among those listed. It SHALL show on the three lists only. The drawer SHALL open on the app's name beside a closing cross, then one card per account naming its address and the domains it covers, with a pill saying Deactivated when the account does not take part and otherwise when it last synced, then, fixed at the bottom, a line and the actions. Pressing a card SHALL open that account's settings. The drawer SHALL NOT list mailboxes. A list screen's bar SHALL carry the burger and SHALL NOT carry the domain buttons.

#### Scenario: Switching to the calendars
- GIVEN the mail list
- WHEN the calendars item of the bottom bar is pressed
- THEN the agenda is swapped in with no slide, the calendars item on the indicator

#### Scenario: An account the filter hides
- GIVEN an account hidden by the filter
- WHEN the drawer opens
- THEN its card's pill says Deactivated

### Requirement: A sync skips what the filter hides
A mail pull SHALL list the shown accounts' mailboxes and SHALL NOT reconcile a mailbox the filter hides, by its account or by its name. The drawer's sync SHALL reconcile every mailbox.

#### Scenario: A hidden mailbox
- GIVEN a mailbox unchecked in the filter
- WHEN the mail list is pulled down
- THEN the mailbox is still offered by the filter, and nothing in it is read or pushed

## REMOVED Requirements
