---
cairn: delta
change: domain-sync-steps
---

## ADDED Requirements

## MODIFIED Requirements

### Requirement: A sync says what it is working on, in every domain
The modal sync dialog SHALL name what the pass is on and what it is doing: the domain being reconciled as its title (*Emails*, *Contacts*, *Calendars*), and the step it stands at as its detail line. The three domains SHALL report both, so a wait reads the same whichever one is being synced. A counted step SHALL count what the domain holds: messages for mail, events for calendars, contacts for the books, singular or plural as the count asks; the steps against the phone SHALL be the contacts' alone.

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

#### Scenario: The agenda's bodies
- GIVEN a calendar pass reading 23 entries
- WHEN the dialog steps to the download
- THEN the detail line reads *Downloading 23 events*, never contacts

## REMOVED Requirements
