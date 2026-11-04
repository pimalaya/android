---
cairn: delta
change: one-onboarding-path
---

## ADDED Requirements

### Requirement: The setup has one path
The connection flow SHALL NOT ask how to set the account up. Once the address is entered and discovered, it SHALL show one screen listing every domain discovery found, in the order of the app's bar, each a checkbox row and nothing else, the same screen whatever the provider. Continue SHALL lead to the sign-in page.

#### Scenario: A Fastmail address
- GIVEN an address whose discovery found mail, contacts and calendars
- WHEN discovery ends
- THEN the domain screen lists the three as checkbox rows, with no sign-in, field or option under them
- AND no question about a standard or an advanced setup was asked

### Requirement: A sign-in says how it connects in a sentence
Each card of the sign-in page SHALL name the domains it covers and say in plain words how they sign in, naming no protocol, host or port. A tune button at the top right of each card SHALL open a menu listing, for each domain of the card, the other options discovery found for it, worded the same way, then *Custom settings*. Picking an option for a domain SHALL move that domain out of the card, into an unsigned card of the same sign-in or a card of its own where its old card stood, and SHALL keep what was typed in the other cards.

#### Scenario: A JMAP server offering OAuth and a password
- GIVEN an address whose server offers JMAP with OAuth and with a password, and IMAP with a password
- WHEN mail is ticked and the sign-in page opens
- THEN mail's card reads that it signs in through the browser
- AND its tune menu lists the password sign-ins as alternatives for mail

#### Scenario: One domain out of a shared grant
- GIVEN a card signing mail, contacts and calendars in through one browser grant
- WHEN mail's IMAP password option is picked in its tune menu
- THEN contacts and calendars stay on the browser card
- AND mail has a password card of its own

### Requirement: One form edits a domain's settings
*Custom settings* in a card's tune menu SHALL turn that domain's card into a form, in the card itself: a server and its port for mail and sending, STARTTLS for sending, a server URL for contacts and calendars, and whether the secret is an API token, prefilled from the domain's discovered IMAP, CardDAV, CalDAV or SMTP endpoint, above the login and password. It SHALL move the domain out of a card covering several, as a pick does, and the form's tune menu SHALL offer *Use discovered settings* to return. The form drives IMAP, SMTP, CardDAV and CalDAV only. A ticked domain discovery found no option for SHALL be planned as a card whose sentence opens the same form, empty. The form SHALL refuse a plain-text server, as the setup does.

#### Scenario: Changing a discovered port
- GIVEN mail on IMAP at port 993
- WHEN *Custom settings* is picked in its tune menu and the port changed
- THEN the card showed the server and port 993 to begin with
- AND mail signs in with the saved settings

#### Scenario: Nothing discovered for calendars
- GIVEN an address whose discovery found no calendar service
- WHEN calendars is ticked and the sign-in page opens
- THEN calendars has a card whose sentence opens the form, empty

### Requirement: Each ticked domain has a page of its own options
After the sign-ins, the flow SHALL show one page per ticked domain, in the bar's order, built from the same rows as that domain's sections in the account settings. Mail's page SHALL offer its mailboxes, every one ticked but the junk and the trash, new-mail notifications and the download window, the mailboxes ticked alone showing and syncing; the contacts and calendars pages SHALL offer their collections and the phone mirror. A domain holding a single collection SHALL show no collection selection, that collection taken.

#### Scenario: One addressbook
- GIVEN a contacts account holding one addressbook
- WHEN the contacts page opens
- THEN it offers the phone mirror and no addressbook selection
- AND the addressbook is synced

### Requirement: A domain syncs as soon as its page is done
The flow SHALL save the account once its sign-ins succeed, marked as being set up until the flow finishes. Continuing past a domain's page SHALL start that domain's first sync in the background, showing nothing while the flow runs. Backing out of the flow SHALL remove the account and what it stored, and an account still marked as being set up when the app starts SHALL be removed the same way.

#### Scenario: Three domains
- GIVEN a flow connecting mail, contacts and calendars
- WHEN the mail page is continued
- THEN mail's first sync runs while the contacts page is shown

#### Scenario: Backing out on the calendars page
- GIVEN a flow whose mail and contacts pages are done
- WHEN the user backs out of the flow
- THEN the account and what its syncs stored are removed

### Requirement: The setup signs in on one page
The setup SHALL gather every sign-in its selection needs on one page and SHALL NOT open a dialog per sign-in. Browser grants SHALL share a card per authorization server and audience; password and token sign-ins SHALL have one card per server, a JMAP session being one server for every domain it serves and IMAP, SMTP, CardDAV and CalDAV one each, even on one host. A card SHALL ask what its method needs: a login and a password, an API token, or a browser grant; a server set through the settings form SHALL be signed in the same way, the form having set the server. Every card SHALL name the domains it signs in for. From the second password card on, a switch *Same login and password as* SHALL name the first password card, off to begin with and available once that card is signed in; with two or more earlier password cards, its label SHALL pick which. Turning it on SHALL sign the card in with that card's login and password against its own server, the card showing no sign-in button of its own; turning it off SHALL undo it and show the card's fields. No card SHALL sign in another. A linked card that fails to sign in SHALL say so and unlink, its fields shown. Each card SHALL report its own outcome, keeping the cards that succeeded; a signed card SHALL fold to its header and a *Sign in again* button drawn as the sign-in button. Connect SHALL stay disabled until every card is signed in.

#### Scenario: Posteo
- GIVEN an address reading mail over IMAP, sending over SMTP, contacts over CardDAV and calendars over CalDAV, all on one host with one password
- WHEN the four domains' sign-in page opens
- THEN it shows four cards: Mail, Mail (sending), Contacts and Calendars
- AND the last three offer "Same login and password as Mail", off and unavailable

#### Scenario: Reusing a signed-in password
- GIVEN the Posteo page, Mail's card signed in
- WHEN the Contacts card's switch is turned on
- THEN the Contacts card signs in with Mail's login and password, and no other card does

#### Scenario: Fastmail over JMAP
- GIVEN an address reading mail, contacts and calendars over one JMAP session
- WHEN the sign-in page opens
- THEN it shows one card for the three

#### Scenario: Another password
- GIVEN the Posteo page, its contacts server refusing Mail's password
- WHEN the Contacts card's switch is turned on
- THEN the Contacts card says the password was not accepted, its switch off and its fields shown

## MODIFIED Requirements
### Requirement: The user sees where a password goes
A sign-in card asking for a password or token SHALL name, before it is sent, the server it signs in to in its subtitle, by host; a server typed by hand is the user's own. A host outside the address's domain is not flagged: providers routinely serve their addresses from another domain.

#### Scenario: Mail hosted elsewhere
- GIVEN `jane@example.org` whose autoconfig names `imaps://imap.fastmail.com:993`
- WHEN the sign-in card shows
- THEN its subtitle names `imap.fastmail.com`, and no other line says where the password goes

### Requirement: An OAuth client is entered on a page
When a server registers no client on its own, or the user asks to use their own, the setup SHALL ask for the client on a page of its own (client ID, optional secret, authorization and token endpoints, scope, redirect URI, the discovered ones prefilled), and SHALL return to the sign-in page with nothing lost.

#### Scenario: A server without dynamic registration
- GIVEN a browser card whose server refuses dynamic client registration
- WHEN its sign-in starts
- THEN the OAuth client page opens with the endpoints prefilled and the client ID empty


### Requirement: The standard setup connects with the best sign-in found
The setup SHALL select for each domain, by default, the best-ranked discovered option: at Google and Microsoft, their own APIs (Gmail, Google Calendar, the People API, Graph) signed in through the app's OAuth registration; elsewhere JMAP over IMAP, SMTP, CardDAV and CalDAV, then OAuth over an API token over a password. The sign-ins SHALL run on the sign-in page (*The setup signs in on one page*).

#### Scenario: A Google address
- GIVEN an address hosted at Google, custom domain included
- WHEN mail, contacts and calendars are ticked and nothing else is chosen
- THEN they connect over Gmail, the People API and Google Calendar, through one Google consent

#### Scenario: A JMAP server offering OAuth and a password
- GIVEN an address whose server offers JMAP with OAuth and with a password, and IMAP with a password
- WHEN mail is ticked and nothing else is chosen
- THEN mail connects over JMAP through OAuth

#### Scenario: Several domains, several servers
- GIVEN three ticked domains at three servers
- WHEN the sign-in page opens
- THEN it shows one card per server, each naming its domain

### Requirement: A connected account is synchronised before the app shows it
Finishing the connection flow SHALL land on the list of the first ticked domain in the bar's order, where the sync dock shows the first syncs still running. A domain whose first sync failed or never started SHALL owe it to the first time its tab is reached, which SHALL sync that domain alone, under the sync dock, and a failed one SHALL stay owed, tried again on the next visit. The user SHALL NOT have to refresh a domain to see what was just connected.

#### Scenario: Contacts and calendars
- GIVEN a flow that ticked contacts and calendars
- WHEN it finishes
- THEN the app lands on the contacts list, whose first sync started when its page was done

#### Scenario: One domain fails
- GIVEN a first calendar sync that fails
- WHEN the calendar tab is opened
- THEN its first sync runs again, the domains that succeeded untouched

### Requirement: The setup names sending in the words of the setup it is in
When mail reads over IMAP signed in with a password or a token, the sign-in page SHALL carry sending as a card of its own, *Mail (sending)*, starting on the best submission endpoint discovery found this client can drive, signed in by an SMTP authentication with nothing sent; its tune menu SHALL list the other discovered endpoints, *Custom settings* and *Do not send from this account*, which removes the card. A browser grant reading mail SHALL cover sending, and JMAP, Gmail and Graph SHALL submit through the session they read with, so neither has a sending card. An address publishing none SHALL start on *Do not send from this account*, with no sending card. Implicit TLS and STARTTLS are both driven. Submission SHALL sign in with what the sending card signed in with: mail's credential when the login and password are mail's, a second credential of the account otherwise. An endpoint offered with a method and without one SHALL be listed once, with its method.

#### Scenario: An address that publishes submission
- GIVEN an address reading mail over IMAP with a password, whose discovery turned up an implicit-TLS SMTP endpoint
- WHEN mail is ticked and the sign-in page opens
- THEN a Mail (sending) card says where it sends
- AND no protocol is named in the sentence

#### Scenario: Another login for sending
- GIVEN the same address, its sending card unlinked and signed in with another login
- WHEN the flow finishes
- THEN mail reads with its own login and sends with the sending card's

#### Scenario: An address that publishes none
- GIVEN an address whose discovery turned up no submission this client can drive
- WHEN mail is ticked and the sign-in page opens
- THEN there is no sending card, and mail does not send

#### Scenario: Not sending
- GIVEN an address that publishes submission
- WHEN the flow finishes on *Do not send from this account*
- THEN the account stores no submit endpoint, and is not offered as a sender

#### Scenario: Sending
- GIVEN an address that publishes submission
- WHEN the flow finishes on its discovered endpoint
- THEN the account stores the submit endpoint, and is offered as a sender

### Requirement: A permission is asked only as its option is turned on
The app SHALL ask the contacts, calendar and notifications permissions only when the user turns on an option needing them: the phone mirror or the notifications switch of a domain page, or the matching option in an account's settings. A refusal SHALL turn that option back off. Nothing else SHALL prompt: not continuing a setup, not opening the app, not a sync. An option whose permission was revoked in the system settings SHALL read as off, and turning it on SHALL ask again.

#### Scenario: Refused in a setup
- GIVEN the contacts page of a setup
- WHEN "Show in phone contacts" is turned on and the permission refused
- THEN the switch is off again, and Continue asks nothing

#### Scenario: Revoked later
- GIVEN an account notifying new mail, its notifications permission then revoked in the system settings
- WHEN its settings are opened
- THEN "Notify new mail" reads off, and turning it on asks the permission

### Requirement: The setups offer new-mail notifications
Mail's page SHALL offer a switch "Notify new mail", off by default, asking the notifications permission on Android 13 and later when it is turned on. Connected with it on, the account SHALL notify new mail and SHALL sync in the background, at the default interval when its interval was off.

#### Scenario: Turned on
- GIVEN mail's page of a setup
- WHEN "Notify new mail" is turned on, the permission granted, and the setup finishes
- THEN the account's settings show "Notify new mail" on and background sync every 15 minutes

#### Scenario: Left off
- GIVEN the same page with the switch left off
- WHEN the setup finishes
- THEN no permission was asked and the account does not notify

### Requirement: The standard setup starts with nothing ticked
The domain screen SHALL open with every domain unticked, so each ticked one shows its sentence row in turn, and Continue SHALL stay disabled until one is ticked. Ticks and selected options SHALL be kept when the flow steps back to this screen.

#### Scenario: A Microsoft address
- GIVEN an address offering mail, contacts and calendars
- WHEN the domain screen is shown
- THEN no domain is ticked, no sentence row shows, and Continue is disabled

## REMOVED Requirements

### Requirement: The setup mode decides what the domain screen asks
Replaced by *The setup has one path*: there is no mode to choose, the alternatives and manual settings live behind each domain's row.

### Requirement: The standard setup asks for one password
Replaced by *The setup signs in on one page*: a password typed once for several domains is the "same login and password" switch of the sign-in page.

### Requirement: The advanced setup signs in on one page
Replaced by *The setup signs in on one page*, which every setup runs.
