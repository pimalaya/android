---
cairn: delta
change: mail-test-round
---

## ADDED Requirements

### Requirement: The drawer's sync covers every domain
The drawer's sync SHALL reconcile contacts, then mail, then calendars, and SHALL report one failure at most, the contacts one first.

#### Scenario: A marker staged on the phone
- GIVEN a message flagged in the reader
- WHEN the drawer's sync runs
- THEN the flag is pushed to the server

### Requirement: A sync skips what the filter hides
A mail pass SHALL list every account's mailboxes and SHALL NOT reconcile a mailbox the filter hides, by its account or by its name.

#### Scenario: A hidden mailbox
- GIVEN a mailbox unchecked in the filter
- WHEN mail is synced
- THEN the mailbox is still offered by the filter, and nothing in it is read or pushed

### Requirement: The filter offers what it can hide
The filter's account axis SHALL list the accounts covering the domain on screen, and SHALL NOT list the on-device account, whose one address book is on the collection axis.

#### Scenario: A contacts-only account on the mail list
- GIVEN an account connected for contacts alone
- WHEN the filter is opened over the mail list
- THEN the account is not listed

### Requirement: A deleted account takes its mail and calendars
Deleting an account SHALL drop its mailboxes and their messages, whatever its outbox still holds, and its calendars and their events. Its contacts SHALL move into the on-device book.

#### Scenario: Deleting a mail account
- GIVEN an account with synced mail
- WHEN it is deleted
- THEN none of its messages is listed

## MODIFIED Requirements

### Requirement: A sync says what it is working on, in every domain
The modal sync dialog SHALL name what the pass is on and what it is doing: the domain being reconciled as its title (*Emails*, *Contacts*, *Calendars*), and the step it stands at as its detail line. The three domains SHALL report both, so a wait reads the same whichever one is being synced.

Neither line SHALL ever be empty while the dialog is up. It SHALL open naming the first domain the pass reaches over a line saying it is preparing, and SHALL replace the title as the pass moves to the next domain and the detail line as it steps.

#### Scenario: A pass over every domain
- GIVEN the drawer's sync
- WHEN it moves from contacts to mail to calendars
- THEN the title reads *Contacts*, then *Emails*, then *Calendars*

#### Scenario: The roster round
- GIVEN a pass that has to list an account's mailboxes or calendars first
- WHEN it starts
- THEN the detail line is set before that round, never a title over a blank line

#### Scenario: The first frame
- GIVEN a sync the user has just asked for
- WHEN the dialog opens, before any round trip
- THEN it names the domain being synced over a line saying it is preparing, rather than one line over an empty one

### Requirement: The setup names sending in the words of the setup it is in
(Scenario added.)

#### Scenario: Sending switched on
- GIVEN an address that publishes submission
- WHEN the flow finishes with sending switched on
- THEN the account stores the submit endpoint, and is offered as a sender

## REMOVED Requirements
