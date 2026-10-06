---
cairn: spec
capability: onboarding
status: current
---

# Onboarding

The connection flow is one address, one question about how much to ask, and one screen per account. Discovery turns the address into services (provider rules over MX, then PACC, CardDAV, IMAP and JMAP), and the domains those services serve are what the screen offers: mail, contacts and calendars, each a switch carrying its name and the glyph the app's own bar draws it with.

An account is the unit. Whatever the run connected is saved once, as one entry naming every domain it covers, and one browser grant covers every domain that chose the same authorization server.

### Requirement: The setup mode decides what the domain screen asks
The connection flow SHALL ask, once the address is entered, whether to set the account up the standard way or step by step, and the answer SHALL decide what the domain screen offers. The standard setup SHALL offer one switch per domain and no configuration; the advanced setup SHALL offer, under each switch, one option per discovered service and authentication method plus manual entry.

#### Scenario: The standard setup
- GIVEN an address whose services are discovered
- WHEN the standard setup is chosen
- THEN each domain is one switch carrying its name and glyph
- AND nothing on the screen names a protocol or an authentication method

#### Scenario: The advanced setup
- GIVEN the same address
- WHEN the advanced setup is chosen
- THEN each switched-on domain lists its configurations, manual entry among them

### Requirement: The standard setup connects with a password alone
The standard setup SHALL connect a domain with the best-ranked discovered configuration that signs in with a login and a password, and SHALL NOT connect one any other way. A domain offering no password sign-in SHALL be shown switched off and unswitchable, naming the setup that can connect it. An address offering none for any domain SHALL send the flow to the advanced setup rather than to an empty screen. Every credential prompt it opens SHALL name the domains it signs in for, and SHALL say how far along the sequence is whenever that sequence has more than one step.

#### Scenario: A password sign-in exists
- GIVEN an address whose mail is discovered with a password sign-in
- WHEN the standard setup switches mail on
- THEN the credential prompt asks for a login and a password
- AND names mail in its title

#### Scenario: Several domains, several servers
- GIVEN a standard setup connecting three domains at three servers
- WHEN the prompts run
- THEN each names its own domain and its place in the sequence
- AND none of them names a protocol or an authentication method

#### Scenario: One domain is out of reach
- GIVEN an address whose contacts are only offered behind a browser grant
- WHEN the standard setup is shown
- THEN the contacts switch is off, unswitchable, and says the advanced setup can connect it

#### Scenario: Nothing is in reach
- GIVEN an address offering no password sign-in at all
- WHEN the standard setup is shown
- THEN the flow offers the advanced setup instead

### Requirement: A connected account is synchronised before the app shows it
Finishing the connection flow SHALL synchronise every domain the account covers in one pass, behind the flow's own loader, and SHALL land on the list of the first domain it covers. The user SHALL NOT have to refresh a domain to see what was just connected.

#### Scenario: An account covering three domains
- GIVEN a connection flow that connected mail, contacts and calendars
- WHEN it finishes
- THEN all three are fetched in one pass
- AND the app lands on the mail list holding the messages that pass fetched

#### Scenario: One domain fails
- GIVEN a pass whose calendar listing fails
- WHEN it finishes
- THEN the domains that succeeded are shown, and the failure is reported once

### Requirement: The setup names sending in the words of the setup it is in
The connection flow SHALL offer where mail is submitted as part of connecting mail, and SHALL name it the way the chosen setup names everything else. The standard setup SHALL offer one switch reading *send mail*, carrying no protocol, on when the discovery run turned up an endpoint this client can drive and off and unswitchable when it did not, naming the setup that can connect it. The advanced setup SHALL list, under a switched-on mail section, one row per discovered submission configuration, a row for none, and manual entry. Implicit TLS only, this client having no STARTTLS step. Submission SHALL sign in with the credential the mail connection signed in with, and the flow SHALL NOT prompt a second time.

#### Scenario: An address that publishes submission
- GIVEN an address whose discovery turned up an implicit-TLS SMTP endpoint
- WHEN the standard setup switches mail on
- THEN a *send mail* switch is shown on under it
- AND no protocol is named anywhere on the screen

#### Scenario: An address that publishes none
- GIVEN an address whose discovery turned up no submission this client can drive
- WHEN the standard setup is shown
- THEN the *send mail* switch is off, unswitchable, and says the advanced setup can connect it

#### Scenario: The same address, advanced
- GIVEN the same address
- WHEN the advanced setup switches mail on
- THEN the section lists its SMTP configurations, a row for none, and manual entry
- AND manual entry asks for a host and builds an implicit-TLS endpoint on port 465 when none was typed

#### Scenario: Sending switched off
- GIVEN an address that publishes submission
- WHEN the flow finishes with sending switched off
- THEN the account stores no submit endpoint, and is not offered as a sender

### Requirement: An account can gain a sender after it is connected
The account settings screen SHALL show, for an account covering mail, where that account submits, and SHALL let it be entered, changed or emptied. What is entered is read the way the advanced setup reads it: a host, or a host and a port, as an implicit-TLS endpoint on port 465 by default. Changing it SHALL leave the account's credential and the endpoint it reads from alone, and SHALL take effect without a sign-in.

#### Scenario: An account connected before submission existed
- GIVEN a mail account carrying no submit endpoint
- WHEN one is entered in its settings
- THEN the account is offered as a sender, with no reconnection and no second sign-in

#### Scenario: Emptying it
- GIVEN a mail account that sends
- WHEN the field is emptied
- THEN the account stores no submit endpoint and is no longer offered as a sender
