---
cairn: delta
change: standard-onboarding-one-password
---

## ADDED Requirements

### Requirement: The standard setup asks for one password
When every domain the standard setup connects signs in with a password, the domain screen SHALL carry one password field, the login being the entered address, and the flow SHALL NOT open a credential prompt per domain. The password SHALL be tried against every password domain, and the outcome SHALL be reported per domain. A domain whose server refuses it SHALL be offered two ways on: continue without it, connecting the domains that accepted, or set it up in the advanced setup. A password every server refuses SHALL be reported on the field and SHALL save nothing.

#### Scenario: One password for three domains
- GIVEN an address whose mail, contacts and calendars are discovered with password sign-ins
- WHEN the standard setup connects all three with one password every server accepts
- THEN the password was typed once
- AND the saved account covers the three domains

#### Scenario: One server refuses
- GIVEN the same address, its calendar server refusing the password
- WHEN the standard setup connects
- THEN mail and contacts read as connected and calendar as not accepted
- AND continuing without the calendar saves an account covering mail and contacts

#### Scenario: Every server refuses
- GIVEN the same address and a wrong password
- WHEN the standard setup connects
- THEN the field says the password is wrong
- AND no account is saved

## MODIFIED Requirements

### Requirement: The setup mode decides what the domain screen asks
The connection flow SHALL start in the standard setup, without asking how much to ask, and SHALL offer the advanced setup as a link on the address screen and beside the standard setup's password field. The standard setup SHALL offer one row per discovered domain, ticked by default, and no configuration; the advanced setup SHALL offer, under each switch, one option per discovered service and authentication method plus manual entry. Two options reading the same, protocol, server and authentication method, SHALL be one: a server taking OAuth through two grants is one OAuth sign-in, and the grant kept is the code grant, a phone having a browser.

#### Scenario: The standard setup
- GIVEN an address whose services are discovered
- WHEN the address is entered
- THEN the domain screen lists each discovered domain, ticked, carrying its name and glyph
- AND nothing on the screen names a protocol or an authentication method

#### Scenario: The advanced setup
- GIVEN the same address
- WHEN the advanced setup link is followed
- THEN each switched-on domain lists its configurations, manual entry among them

### Requirement: The standard setup connects with the best sign-in found
The standard setup SHALL connect a domain without asking how: at Google and Microsoft, with their own APIs (Gmail, Google Calendar, the People API, Graph) signed in through the app's OAuth registration; elsewhere with the best-ranked discovered configuration, JMAP over IMAP, SMTP, CardDAV and CalDAV, then OAuth over an API token over a password. The user SHALL choose only which domains to connect and, for password sign-ins, type one password. A domain offering no sign-in at all SHALL NOT be listed. An address offering none for any domain SHALL send the flow to the advanced setup rather than to an empty screen. When the chosen domains need more than one kind of sign-in, a password and a browser grant, each SHALL run once, for every domain it covers.

#### Scenario: A Google address
- GIVEN an address hosted at Google, custom domain included
- WHEN the standard setup connects mail, contacts and calendars
- THEN they connect over Gmail, the People API and Google Calendar, through one Google consent

#### Scenario: A JMAP server offering OAuth and a password
- GIVEN an address whose server offers JMAP with OAuth and with a password, and IMAP with a password
- WHEN the standard setup connects mail
- THEN mail connects over JMAP through OAuth

#### Scenario: A password sign-in alone
- GIVEN an address whose mail is discovered with a password sign-in only
- WHEN the standard setup lists mail
- THEN the domain screen asks for a password, the login being the address
- AND no credential dialog opens

#### Scenario: Nothing is in reach
- GIVEN an address offering no sign-in this client drives
- WHEN the address is entered
- THEN the flow offers the advanced setup instead

### Requirement: The setup names sending in the words of the setup it is in
The connection flow SHALL offer where mail is submitted as part of connecting mail, and SHALL name it the way the chosen setup names everything else. The standard setup SHALL connect the discovered endpoint this client can drive with mail and SHALL ask nothing about it; when none was discovered, the account SHALL be saved without one, a sender being addable from its settings. The advanced setup SHALL list, under a switched-on mail section, one row per discovered submission configuration, a row for none, and manual entry. Implicit TLS and STARTTLS are both driven. Submission SHALL sign in with the credential the mail connection signed in with, and the flow SHALL NOT prompt a second time.

The advanced setup SHALL list one row per discovered submission endpoint and sign-in method, as it lists reading rows, and SHALL drop a row naming no method when the same endpoint is offered with one.

#### Scenario: An address that publishes submission
- GIVEN an address whose discovery turned up an implicit-TLS SMTP endpoint
- WHEN the standard setup connects mail
- THEN the account stores the submit endpoint and is offered as a sender
- AND nothing on the screen asked about sending

#### Scenario: An address that publishes none
- GIVEN an address whose discovery turned up no submission this client can drive
- WHEN the standard setup connects mail
- THEN the account stores no submit endpoint, and its settings can add one

#### Scenario: The same address, advanced
- GIVEN the same address
- WHEN the advanced setup switches mail on
- THEN the section lists its SMTP configurations, a row for none, and manual entry
- AND manual entry asks for a host and builds an implicit-TLS endpoint on port 465 when none was typed

#### Scenario: One server found twice
- GIVEN an SMTP endpoint discovered once with a password method and once with none
- WHEN the advanced setup switches mail on
- THEN it is listed once, with its method

## REMOVED Requirements
