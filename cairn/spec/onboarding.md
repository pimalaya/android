---
cairn: spec
capability: onboarding
status: current
---

# Onboarding

The connection flow is one address, one question about how much to ask, and one screen per account. Discovery turns the address into services (the fixed provider rules first, then PACC, CardDAV, CalDAV, autoconfig and JMAP), and the domains those services serve are what the screen offers: mail, contacts and calendars, each a switch carrying its name and the glyph the app's own bar draws it with.

An account is the unit. Whatever the run connected is saved once, as one entry naming every domain it covers, and one browser grant covers every domain that chose the same authorization server for the same API.

### Requirement: The setup mode decides what the domain screen asks
The connection flow SHALL ask, once the address is entered, whether to set the account up the standard way or step by step, and the answer SHALL decide what the domain screen offers. The standard setup SHALL offer one switch per domain and no configuration; the advanced setup SHALL offer, under each switch, one option per discovered service and authentication method plus manual entry. Two options reading the same, protocol, server and authentication method, SHALL be one: a server taking OAuth through two grants is one OAuth sign-in, and the grant kept is the code grant, a phone having a browser.

#### Scenario: The standard setup
- GIVEN an address whose services are discovered
- WHEN the standard setup is chosen
- THEN each domain is one switch carrying its name and glyph
- AND nothing on the screen names a protocol or an authentication method

#### Scenario: The advanced setup
- GIVEN the same address
- WHEN the advanced setup is chosen
- THEN each switched-on domain lists its configurations, manual entry among them

### Requirement: The standard setup connects with the best sign-in found
The standard setup SHALL connect a domain without asking how: at Google and Microsoft, with their own APIs (Gmail, Google Calendar, the People API, Graph) signed in through the app's OAuth registration; elsewhere with the best-ranked discovered configuration, JMAP over IMAP, SMTP, CardDAV and CalDAV, then OAuth over an API token over a password. The user SHALL choose only which domains to connect. A domain offering no sign-in at all SHALL be shown switched off and unswitchable, naming the setup that can connect it. An address offering none for any domain SHALL send the flow to the advanced setup rather than to an empty screen. Every credential prompt it opens SHALL name the domains it signs in for, and SHALL say how far along the sequence is whenever that sequence has more than one step.

#### Scenario: A Google address
- GIVEN an address hosted at Google, custom domain included
- WHEN the standard setup switches mail, contacts and calendars on
- THEN they connect over Gmail, the People API and Google Calendar, through one Google consent

#### Scenario: A JMAP server offering OAuth and a password
- GIVEN an address whose server offers JMAP with OAuth and with a password, and IMAP with a password
- WHEN the standard setup switches mail on
- THEN mail connects over JMAP through OAuth

#### Scenario: A password sign-in alone
- GIVEN an address whose mail is discovered with a password sign-in only
- WHEN the standard setup switches mail on
- THEN the credential prompt asks for a login and a password
- AND names mail in its title

#### Scenario: Several domains, several servers
- GIVEN a standard setup connecting three domains at three servers
- WHEN the prompts run
- THEN each names its own domain and its place in the sequence
- AND none of them names a protocol or an authentication method

#### Scenario: Nothing is in reach
- GIVEN an address offering no sign-in this client drives
- WHEN the standard setup is shown
- THEN the flow offers the advanced setup instead

### Requirement: A connected account is synchronised before the app shows it
Finishing the connection flow SHALL land on the list of the first domain the account covers, mail first, and each domain the account covers SHALL owe its first sync to the first time its tab is reached: that tab SHALL sync that domain alone, under the list's sync strip. A first sync that failed SHALL stay owed, tried again on the next visit. The user SHALL NOT have to refresh a domain to see what was just connected.

#### Scenario: An account covering three domains
- GIVEN a connection flow that connected mail, contacts and calendars
- WHEN it finishes
- THEN the app lands on the mail list, whose first sync fetches the newest chunk of each mailbox and nothing else
- AND the contacts and the calendars sync, each under its strip, the first time their tab is opened

#### Scenario: One domain fails
- GIVEN a first calendar sync that fails
- WHEN the calendar tab is opened again
- THEN its first sync runs again, the domains that succeeded untouched

### Requirement: The setup names sending in the words of the setup it is in
The connection flow SHALL offer where mail is submitted as part of connecting mail, and SHALL name it the way the chosen setup names everything else. The standard setup SHALL offer one switch reading *send mail*, carrying no protocol, on when the discovery run turned up an endpoint this client can drive and off and unswitchable when it did not, naming the setup that can connect it. The advanced setup SHALL list, under a switched-on mail section, one row per discovered submission configuration, a row for none, and manual entry. Implicit TLS and STARTTLS are both driven. Submission SHALL sign in with the credential the mail connection signed in with, and the flow SHALL NOT prompt a second time.

The advanced setup SHALL list one row per discovered submission endpoint and sign-in method, as it lists reading rows, and SHALL drop a row naming no method when the same endpoint is offered with one.

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

#### Scenario: Sending switched on
- GIVEN an address that publishes submission
- WHEN the flow finishes with sending switched on
- THEN the account stores the submit endpoint, and is offered as a sender

#### Scenario: One server found twice
- GIVEN an SMTP endpoint discovered once with a password method and once with none
- WHEN the advanced setup switches mail on
- THEN it is listed once, with its method

### Requirement: An account can gain a sender after it is connected
The account settings screen SHALL show, for an account covering mail, where that account submits, and SHALL let it be entered, changed or emptied. What is entered is read the way the advanced setup reads it: a host, or a host and a port, STARTTLS on port 587 and implicit TLS otherwise, on port 465 by default. Changing it SHALL leave the account's credential and the endpoint it reads from alone, and SHALL take effect without a sign-in.

#### Scenario: An account connected before submission existed
- GIVEN a mail account carrying no submit endpoint
- WHEN one is entered in its settings
- THEN the account is offered as a sender, with no reconnection and no second sign-in

#### Scenario: Emptying it
- GIVEN a mail account that sends
- WHEN the field is emptied
- THEN the account stores no submit endpoint and is no longer offered as a sender

### Requirement: A provider's own rules are discovered first
The protocol sweep SHALL run the fixed provider rules (by domain, then by MX) before every other mechanism, so an address hosted at Google or Microsoft is offered every domain those rules name, a custom domain included.

#### Scenario: A Microsoft address
- GIVEN an address whose mail exchanges live at Microsoft
- WHEN discovery runs
- THEN mail (IMAP, Graph), sending (SMTP), contacts (Graph) and calendars (Graph) are offered
- AND each is offered once per protocol, its two OAuth grants being one sign-in

#### Scenario: A Google address
- GIVEN an address whose mail exchanges live at Google
- WHEN discovery runs
- THEN mail (IMAP, Gmail API), sending (SMTP), contacts (CardDAV, People API) and calendars (CalDAV, Calendar API) are offered

### Requirement: A provider grant is the app's own
A browser grant against Google's or Microsoft's authorization server SHALL run with the app's registered client and SHALL NOT send an RFC 8707 resource. Domains SHALL share one grant only when their scopes address the same API.

#### Scenario: Microsoft mail and calendars
- GIVEN mail and calendars both connected through Microsoft
- WHEN the sign-in sequence runs
- THEN it asks for two consents, Outlook's and Graph's

### Requirement: A permission is asked only as its option is turned on
The app SHALL ask the contacts, calendar and notifications permissions only when the user turns on an option needing them: a phone box or the notifications box of a setup, or the matching option in an account's settings. A refusal SHALL turn that option back off. Nothing else SHALL prompt: not continuing a setup, not opening the app, not a sync. An option whose permission was revoked in the system settings SHALL read as off, and turning it on SHALL ask again.

#### Scenario: Refused in a setup
- GIVEN the standard setup with Contacts ticked
- WHEN "Show in phone contacts" is turned on and the permission refused
- THEN the box is unticked again, and Continue asks nothing

#### Scenario: Revoked later
- GIVEN an account notifying new mail, its notifications permission then revoked in the system settings
- WHEN its settings are opened
- THEN "Notify new mail" reads off, and turning it on asks the permission

### Requirement: The setups offer new-mail notifications
Both setups SHALL offer, for an account covering mail, a box "Notify new mail", unticked by default, asking the notifications permission on Android 13 and later when it is turned on. Connected with it on, the account SHALL notify new mail and SHALL sync in the background, at the default interval when its interval was off.

#### Scenario: Turned on
- GIVEN the standard setup with Mail ticked
- WHEN "Notify new mail" is turned on, the permission granted, and the setup connects
- THEN the account's settings show "Notify new mail" on and background sync every 15 minutes

#### Scenario: Left off
- GIVEN the same setup with the box left unticked
- WHEN it connects
- THEN no permission was asked and the account does not notify

### Requirement: The standard setup starts with nothing ticked
The standard setup SHALL open with every domain unticked, so each ticked one shows its options to read in turn, and Continue SHALL stay disabled until one is ticked. Ticks SHALL be kept when the flow steps back to this screen.

#### Scenario: A Microsoft address
- GIVEN an address offering mail, contacts and calendars
- WHEN the standard setup is shown
- THEN no domain is ticked, no option row shows, and Continue is disabled
