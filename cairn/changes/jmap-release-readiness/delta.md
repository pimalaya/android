---
cairn: delta
change: jmap-release-readiness
---

Folds into `spec/onboarding.md`, `spec/mail.md`, `spec/calendar.md` and `spec/carddav-sync.md`.

## ADDED Requirements

### Requirement: A JMAP domain is connected only where the session serves it
Once a JMAP sign-in is accepted, on a password or after a browser grant, the connection flow SHALL read the session resource (RFC 8620 section 2) and connect over JMAP only the domains whose capability the session advertises with a primary account: mail with `urn:ietf:params:jmap:mail` and `urn:ietf:params:jmap:submission`, contacts with `urn:ietf:params:jmap:contacts`, calendars with `urn:ietf:params:jmap:calendars`. A domain the session does not serve SHALL be left out alone, said so beside it, and SHALL NOT fail the sign-in of the domains it does serve.

#### Scenario: A server without JMAP calendars
- GIVEN a JMAP server whose session advertises mail, submission and contacts
- WHEN the standard setup connects mail, contacts and calendars over JMAP with one password
- THEN mail and contacts connect, and calendars are shown as not offered by the server, for the setup to continue without them

#### Scenario: The same server through a browser grant
- GIVEN the same server, signed in through OAuth
- WHEN the grant comes back
- THEN the account is saved with mail and contacts, and the calendars left out are said so

### Requirement: A JMAP account sends through its session
A JMAP mail account SHALL submit over the session it reads from (RFC 8621 section 7), with no submit endpoint of its own: the identity whose address is the message's sender (`Identity/get`, a `*@domain` identity matching any address of its domain), the message with its `Bcc` header taken out uploaded as a blob, imported into the mailbox with the Drafts role under `$draft` and `$seen`, then submitted by `EmailSubmission/set` with the envelope its address headers name, the `Bcc` among them, and `onSuccessUpdateEmail` moving it from Drafts to the mailbox with the Sent role and unsetting `$draft`; with no Sent mailbox, `onSuccessDestroyEmail` removes it. A refusal RFC 8621 or RFC 8620 names for the message or its sender (`forbiddenFrom`, `forbiddenMailFrom`, `forbiddenToSend`, `invalidEmail`, `noRecipients`, `invalidRecipients`, `tooManyRecipients`, `tooLarge`, `forbidden`, `overQuota`), a message past the session's `maxSizeUpload`, no matching identity and no Drafts mailbox SHALL be permanent, the draft imported for it destroyed; anything else SHALL be transient. The sent copy SHALL be staged in Sent at queue time and landed by the `Message-ID` the listing reads, as on Graph and Gmail.

#### Scenario: A message to two recipients and a blind one
- GIVEN a JMAP account with an identity for its address, a Drafts and a Sent mailbox
- WHEN the outbox drains a message to Ada, copying Bob, blind to Carol
- THEN the uploaded message names no `Bcc`, the submission's envelope goes to all three, and the copy lands in Sent without `$draft`

#### Scenario: A sender the server refuses
- GIVEN a server answering `forbiddenFrom`
- WHEN the outbox drains the message
- THEN it is parked as refused, and the draft imported for it is destroyed

#### Scenario: An account saved by an earlier version
- GIVEN a JMAP mail account stored with no submit endpoint
- WHEN the composer opens after the upgrade
- THEN the account is offered as a sender, with nothing to set up

### Requirement: A JMAP calendar is read only
A JMAP calendar SHALL list as not writable and its backend SHALL report `writesEvents` false until `CalendarEvent/set` is written. The agenda's add button SHALL NOT offer it, the entry page SHALL show its entries without a save or a delete and say why, and a create, an edit or a delete staged on one by an earlier build SHALL be refused for good (422) when pushed, the row saying so, rather than failing every pass.

#### Scenario: Adding to a JMAP calendar
- GIVEN an account whose calendars are all JMAP
- WHEN the agenda's add button is pressed
- THEN no entry opens, and the line says JMAP calendars are read only for now

#### Scenario: An edit staged before the upgrade
- GIVEN an edit staged on a JMAP calendar entry by an earlier build
- WHEN the calendar syncs
- THEN the edit is refused and shown so, and the calendar's other changes sync

### Requirement: A JMAP calendar is listed a page at a time
A JMAP calendar round SHALL list its events by `CalendarEvent/query` pages of at most the session's `maxObjectsInGet`, until the server's `total` is reached or a page comes back empty. A listing that stops short of the `total` the server reported SHALL NOT be a complete round: its events are taken, and nothing missing from it is retired.

#### Scenario: A calendar larger than one page
- GIVEN a calendar of 1,200 events and a server answering 500 a page
- WHEN it syncs
- THEN three pages are read and the round is complete

#### Scenario: A server stopping short
- GIVEN a server reporting 1,200 events and answering an empty page at 500
- WHEN it syncs
- THEN the 500 are taken and none of the other 700 is retired

### Requirement: A JMAP address book is read a page at a time
A JMAP contacts round SHALL list the account's cards by `ContactCard/query` pages of at most the session's `maxObjectsInGet`, reading the `ContactCard` state first, so that RFC 8620 section 5.1's `requestTooLarge` never refuses a large book; a delta SHALL read its changed cards in chunks of the same size.

#### Scenario: A book of 3,000 cards
- GIVEN a server whose `maxObjectsInGet` is 1,000
- WHEN the account's first contacts round runs
- THEN three pages are read, and the delta resumes from the state read before them

## MODIFIED Requirements

### Requirement: The standard setup connects with the best sign-in found
The standard setup SHALL connect a domain without asking how: at Google and Microsoft, with their own APIs (Gmail, Google Calendar, the People API, Graph) signed in through the app's OAuth registration; elsewhere with the best-ranked discovered configuration for that domain, then OAuth over an API token over a password. The ranking SHALL follow what the app can do over each protocol for the domain: JMAP over IMAP and SMTP for mail and over CardDAV for contacts, and CalDAV over JMAP for calendars while a JMAP calendar is read only. The user SHALL choose only which domains to connect. A domain offering no sign-in at all SHALL be shown switched off and unswitchable, naming the setup that can connect it. An address offering none for any domain SHALL send the flow to the advanced setup rather than to an empty screen. Every credential prompt it opens SHALL name the domains it signs in for, and SHALL say how far along the sequence is whenever that sequence has more than one step.

#### Scenario: A Google address
- GIVEN an address hosted at Google, custom domain included
- WHEN the standard setup switches mail, contacts and calendars on
- THEN they connect over Gmail, the People API and Google Calendar, through one Google consent

#### Scenario: A JMAP server offering OAuth and a password
- GIVEN an address whose server offers JMAP with OAuth and with a password, and IMAP with a password
- WHEN the standard setup switches mail on
- THEN mail connects over JMAP through OAuth

#### Scenario: A JMAP server with CalDAV beside it
- GIVEN an address whose discovery turns up JMAP and CalDAV
- WHEN the standard setup switches calendars on
- THEN calendars connect over CalDAV

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

### Requirement: A mail account carries where it submits and where its trash is
A mail connection SHALL carry the endpoint mail is submitted through, beside the one it is read from, chosen in the connection flow from what discovery turned up or from what was entered by hand, and stored with it. Implicit TLS only: this client has no STARTTLS step, and driving a `starttls` endpoint as if it were implicit would hand a message over in the clear rather than fail. Every other domain carries none, and so does a mail account reading over JMAP, or whose sending was switched off. The sending question, headed Submission, SHALL be asked only once mail is read over IMAP, discovered or typed, or over Graph, and its rows SHALL follow that choice: IMAP reading is offered the SMTP servers, one row per server and sign-in method as the reading rows are, those reading the same collapsed and only those signing in the way mail does offered, since sending reuses its credential, and manual entry, Graph reading Graph alone, Gmail reading Gmail alone, and both the row for not sending. A Graph or Gmail account sending SHALL carry its own `msgraph://` or `google://` base as its submit endpoint.

Whether an account sends SHALL be read from the account trait `submitsOverSession` first: a JMAP, Graph or Gmail account submits through the session it reads from, and is a sender whatever endpoint it stores; any other account is a sender only with a submit endpoint.

The account SHALL also record the mailbox the server marks `\Trash` (RFC 6154), refreshed by every mail sync from the roster the LIST builds, and whether its last session can expunge one message alone (`UIDPLUS` on IMAP, always elsewhere), so a delete decides between a move, a delete for good and a marker with no round trip.

#### Scenario: An address that publishes both
- GIVEN an address whose discovery turns up IMAP and SMTP
- WHEN the mail domain is connected over IMAP with sending on
- THEN the account stores both endpoints

#### Scenario: An account stored before submission existed
- GIVEN an IMAP account connected by an earlier version
- WHEN it is read back
- THEN it carries no submit endpoint, and is not offered as a sender until one is entered in its settings

#### Scenario: A JMAP account stored with no submit endpoint
- GIVEN a JMAP account connected by an earlier version
- WHEN it is read back
- THEN it is offered as a sender, and its settings ask for no sending server

#### Scenario: A roster that found a trash
- GIVEN a server marking one mailbox `\Trash`
- WHEN the account is synced
- THEN that mailbox is recorded, and read back with no network to ask

## REMOVED Requirements
