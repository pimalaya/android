---
cairn: change
id: provider-accounts
status: landed
---

# Delta

## ADDED Requirements

### Requirement: A provider's own rules are discovered first
The protocol sweep SHALL run the fixed provider rules (by domain, then by MX) before every other mechanism, so an address hosted at Google or Microsoft is offered every domain those rules name, a custom domain included.

#### Scenario: A Microsoft address
- GIVEN an address whose mail exchanges live at Microsoft
- WHEN discovery runs
- THEN mail (IMAP), sending (SMTP), contacts (Graph) and calendars (Graph) are offered

#### Scenario: A Google address
- GIVEN an address whose mail exchanges live at Google
- WHEN discovery runs
- THEN mail (IMAP), sending (SMTP), contacts (CardDAV, People API) and calendars (CalDAV) are offered

### Requirement: Mail signs in with a token
An IMAP or SMTP connection whose credential is an OAuth token SHALL authenticate with SASL `XOAUTH2`, the user being the one its URL names. A mail connection signed in through a browser grant SHALL carry the address as that user, on the endpoint it reads from and on the one it submits to.

#### Scenario: A Microsoft mailbox
- GIVEN mail connected through a Microsoft grant
- WHEN a pass opens the mailbox
- THEN the session authenticates with `XOAUTH2` as the address

### Requirement: Submission speaks STARTTLS
An `smtp://` submission endpoint SHALL be upgraded to TLS with `STARTTLS` before anything is authenticated or sent, and SHALL be refused when the server sends anything past its `220` reply. The setup SHALL offer such endpoints beside implicit-TLS ones.

#### Scenario: Port 587
- GIVEN a submission endpoint on port 587 with STARTTLS
- WHEN a message is submitted
- THEN the session is encrypted before `AUTH`, and nothing goes over the plain socket but `EHLO` and `STARTTLS`

### Requirement: A Microsoft calendar runs over Graph
A calendar connection behind the `msgraph://` marker SHALL list the user's calendars and read their events as iCalendar, a series with its exceptions as one entry. Every pass SHALL list the calendar in full. A write SHALL be refused when the event's revision moved since the edit was staged.

#### Scenario: An event edited on Outlook meanwhile
- GIVEN an entry edited here and on Outlook since the last pass
- WHEN the sync pushes the edit
- THEN Graph is not written and the edit stays staged

### Requirement: A provider grant is the app's own
A browser grant against Google's or Microsoft's authorization server SHALL run with the app's registered client and SHALL NOT send an RFC 8707 resource. Domains SHALL share one grant only when their scopes address the same API.

#### Scenario: Microsoft mail and calendars
- GIVEN mail and calendars both connected through Microsoft
- WHEN the sign-in sequence runs
- THEN it asks for two consents, Outlook's and Graph's

## MODIFIED Requirements

### Requirement: The setup names sending in the words of the setup it is in
The connection flow SHALL offer where mail is submitted as part of connecting mail, and SHALL name it the way the chosen setup names everything else. The standard setup SHALL offer one switch reading *send mail*, carrying no protocol, on when the discovery run turned up an endpoint this client can drive and off and unswitchable when it did not, naming the setup that can connect it. The advanced setup SHALL list, under a switched-on mail section, one row per discovered submission configuration, a row for none, and manual entry. Implicit TLS and STARTTLS are both driven. Submission SHALL sign in with the credential the mail connection signed in with, and the flow SHALL NOT prompt a second time.

## REMOVED Requirements
