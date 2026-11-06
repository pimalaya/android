---
cairn: delta
change: account-settings-page
---

Folds into `spec/offline-store.md`, beside "An account that is off takes no part"; the Tasks requirement into `spec/tasks.md`.

## ADDED Requirements

### Requirement: An account's settings apply as they are changed
Every setting on an account's settings page SHALL take effect when it is changed, with no save step: the account switch, each addressbook's box and server sync, the phone switches, the mail sync period and download policy, and the sending server. The page SHALL show each setting's current value.

#### Scenario: Switching an addressbook off
- GIVEN an account's settings page with an addressbook subscribed
- WHEN its box is unticked under *Subscribed addressbooks* and the page is closed
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

### Requirement: An account's settings read as the setup's
An account's settings page SHALL draw its domains as the setup's settings page does: one card, a section per domain the account covers led by its tile, picked, holding the setup's rows first. Mail: *Notify new mail*, *Subscribed mailboxes*, *Sync period*, then *Mail on this phone* and *Write rich messages*. Mail (sending): *Your name*, then *Send through* when mail does not submit through its session. Contacts: *Show in phone contacts*, *Subscribed addressbooks*. Calendar: *Show in phone calendar*, *Subscribed calendars*, *Tasks*. An *Account* card before it SHALL hold *Use this account* and *Sync in the background*, and a *Sign-in* card after it the servers. The phone's contacts and calendar SHALL be one switch per domain: on, every subscribed addressbook, or every calendar of the account, shows on the phone, the permission asked first and a refusal leaving the switch off; off, none does. *Subscribed addressbooks* SHALL open the account's addressbooks, a box per book and, under a ticked one, *Sync with the server*. *Subscribed mailboxes* and *Subscribed calendars* SHALL count what the account syncs and open the choice of it.

#### Scenario: A calendar account on the phone
- GIVEN an account with the calendars Work and Home, Home kept off the phone before
- WHEN *Show in phone calendar* is turned on and the permission granted
- THEN Work and Home both show in the phone's calendar

## MODIFIED Requirements

### Requirement: An account says whether it takes tasks
Account settings SHALL carry a *Tasks* row in the Calendar section for an account holding calendars: the calendars taking tasks ("In Work, Home"), or the line saying none does, naming Microsoft's or Google's own service when it is theirs. Onboarding SHALL say it in one line under a calendar option that is not CalDAV.

#### Scenario: A Microsoft account
- GIVEN an account whose calendars run over Graph
- WHEN its settings open
- THEN the Tasks row says tasks are not available there, Microsoft keeping them in its own service

## REMOVED Requirements
