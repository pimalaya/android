---
cairn: delta
change: account-settings-page
---

Folds into `spec/offline-store.md`, beside "An account that is off takes no part".

## ADDED Requirements

### Requirement: An account's settings apply as they are changed
Every setting on an account's settings page SHALL take effect when it is changed, with no save step: the account switch, each addressbook's switches, the mail sync period and download policy, and the sending server. The page SHALL show each setting's current value.

#### Scenario: Switching an addressbook off
- GIVEN an account's settings page with an addressbook switched on
- WHEN its switch is turned off and the page is closed
- THEN the addressbook is off, without any save action

#### Scenario: Picking a sync period
- GIVEN the mail sync period row reading "The last 3 months"
- WHEN "The last month" is picked
- THEN the row reads "The last month" and the narrower bound applies

### Requirement: An account's settings show what it connects to
An account's settings page SHALL show the account's identity (its address, the domains it covers and its sync state) and, read only, each server it connects to with the domains, protocols and sign-in method it serves.

#### Scenario: One server for contacts and calendars
- GIVEN an account reading contacts and calendars over CardDAV and CalDAV on one host
- WHEN its settings page is opened
- THEN one server row names that host with "Contacts, Calendar"

### Requirement: Mail goes out under the account's name
A mail account's settings SHALL offer the name its mail is sent under, and a message sent from it SHALL carry that name as the display part of its `From` header, the bare address when none is set.

#### Scenario: Sending with a name
- GIVEN an account whose name is set to "Alex Doe"
- WHEN a message is sent from it
- THEN its `From` header reads `Alex Doe <alex@example.org>`

## MODIFIED Requirements

## REMOVED Requirements
