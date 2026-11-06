---
cairn: delta
change: one-onboarding-path
---

## ADDED Requirements

### Requirement: The setup has one path
The connection flow SHALL NOT ask how to set the account up. It SHALL run the same pages for every address: the address, the servers page, a page per sign-in that asks something, then the settings page.

#### Scenario: A Fastmail address
- GIVEN an address whose discovery finds mail, contacts and calendars
- WHEN the flow runs
- THEN it shows the address, "Review your domains", the sign-in pages and "Review your settings"
- AND no question about a standard or an advanced setup is asked

### Requirement: Discovery runs on the address's Continue
Continue on the address SHALL run discovery, the button dimmed with its spinner at its end and the step answering nothing, the back button included, then open the servers page. A discovery that failed outright SHALL be reported, the address kept, and Continue SHALL run it again.

#### Scenario: No network
- GIVEN a discovery that failed for want of a network
- WHEN Continue is tapped
- THEN the error is reported, the address stays, and Continue runs discovery again

### Requirement: The servers page says how each domain connects
The servers page "Review your domains" SHALL show one card holding a section per domain under hairlines, in the bar's order: Mail, *Mail (sending)*, Contacts, Calendar, every section shown whatever is picked. Each section SHALL lead with its domain's glyph on a tile that is its tick, the row toggling it: filled with the accent and badged with a check while picked, the page tone and ringed otherwise; then its name, over a line: how it connects with its best-ranked discovered option in a sentence (*The setup connects with the best sign-in found*), naming no protocol, host or port; "Connected" for a domain the account already has; "Nothing found" for a domain discovery found nothing for. A section SHALL start picked when discovery found its domain and the account does not have it yet, and Continue SHALL stay disabled while no domain is picked. A section SHALL carry its settings button, disabled while the section is not picked, and while picked its form once shown. *Mail (sending)* SHALL be greyed out and unpicked while Mail is not picked, and SHALL come back as it was when Mail is picked again. Over JMAP, Gmail or Graph it SHALL stay picked with Mail, its tile fixed; through a server of its own it SHALL start picked when discovery found one, and unpicked it SHALL connect the account without sending.

#### Scenario: A Google address
- GIVEN an address hosted at Google
- WHEN the servers page opens
- THEN Mail, Mail (sending), Contacts and Calendar are picked, each reading "With your Google account, in the browser"

#### Scenario: Nothing found for calendars
- GIVEN an address whose discovery found no calendar service
- WHEN the servers page opens
- THEN the Calendar section is not picked and reads "Nothing found"
- AND picking it opens its empty form under "Nothing could be discovered for example.org. Set it up manually."

#### Scenario: Mail left out
- GIVEN the servers page with Mail and Mail (sending) picked
- WHEN Mail's row is tapped
- THEN Mail reads not picked and Mail (sending) is greyed out, no section leaving the page
- AND tapping Mail again brings Mail (sending) back picked

### Requirement: A card's settings list what discovery found
A picked section's settings button SHALL list *Auto*, which puts the section back on what discovery ranked first, then every option discovery found for it by protocol and sign-in method ("JMAP · OAuth 2.0"), naming the server only where two items would read the same, none marked, then *Custom settings*. Picking an option SHALL fill the section's form from it: a server to type for IMAP, SMTP, CardDAV and CalDAV; for a JMAP session or a provider's API its server said and not editable, its sign-in picked among the ones found for that server. *Custom settings* SHALL show the form, filled from the option in use when that option has a server to type, empty otherwise. The form SHALL ask a server and its port for mail and sending, a *Security* for sending picked in a menu (STARTTLS or SSL/TLS, nothing in clear: *Setup accepts encrypted servers only*), a server URL for contacts and calendars, and *Auth*, picked in a menu among a password, an API token and OAuth; each field SHALL carry its name inside it, before its value; an edit of the server, the security or the sign-in makes the section custom. OAuth SHALL be offered for sending only when mail signs in through the browser, whose grant covers it. A section discovery found nothing for SHALL open on the form, empty, with the line "Nothing could be discovered for <domain>. Set it up manually." between its title and its fields, and no settings button. A picked section's form left without a server SHALL be refused on Continue.

#### Scenario: IMAP rather than JMAP
- GIVEN an address whose discovery found JMAP with OAuth and IMAP with a password
- WHEN the Mail section's settings list IMAP and it is picked
- THEN the section's form shows imap.fastmail.com, 993 and Password, and sending gets a section of its own

#### Scenario: Changing a discovered port
- GIVEN mail discovered on IMAP at imap.example.org, port 993
- WHEN *Custom settings* is picked and the port changed
- THEN the section reads as custom and connects to the typed port

#### Scenario: Back to what was found
- GIVEN the Mail section's form changed by hand
- WHEN *Auto* is picked
- THEN the section reads its sentence again and connects with discovery's best option

### Requirement: A card's OAuth application is picked on the servers page
A reading form signing in with OAuth SHALL show an *Application* row under *Auth*, picked in a menu: *Pimalaya*, the app's own (its registration at Google and Microsoft, one registered automatically at an issuer), where the server has it, and *Custom*. *Custom* SHALL show the user's client: the client ID, an optional secret and the redirect, with the issuer at an issuer's server, the authorization and token URLs and the scope elsewhere, prefilled from the server, the client ID to enter. A server with no application of the app's own SHALL start on *Custom*. The grant SHALL run with the application picked, the pages it covers sharing one grant only when they share it. Continue SHALL refuse *Custom* without a client ID or with a redirect the app cannot receive (an http://127.0.0.1 loopback port or one of its schemes), and *Pimalaya* at a typed server that has no application of the app's own. Sending has no application of its own: mail's grant covers it.

#### Scenario: Google
- GIVEN an address hosted at Google, the Mail section's settings opened on "Gmail API · OAuth 2.0"
- WHEN the form shows
- THEN *Application* reads Pimalaya, no client field shows, and Continue runs the usual Google grant

#### Scenario: Your own client at an issuer
- GIVEN a Fastmail address, the Mail section on "JMAP · OAuth 2.0"
- WHEN *Custom* is picked, a client ID typed, then Continue and Mail's page continued
- THEN the grant runs with that client at the issuer's endpoints, and no client is registered

### Requirement: Each sign-in has a page when it asks something
After the servers page the domains SHALL be grouped by sign-in, in their order: one browser grant for the domains of one authorization server, audience and client; a login and password, or an API token, per domain, mail's and sending's apart, as a provider may give each service its own (app passwords); sending through mail's sign-in with mail. Each sign-in SHALL have a page: a browser grant's titled with its provider over the domains it covers ("For Mail, Mail (sending), Contacts, Calendar."), a password's or token's with its domain; holding a sign-in card: every host its password or token goes to, how it signs in, and what that needs, each field named inside it. A lone browser grant SHALL have no page: the servers page's Continue SHALL open the browser at once, its page shown only when the grant left a domain to ask again. The page SHALL have no sign-in button: Continue SHALL sign in at every server it covers and open the next page asking something, the settings page after the last; a sign-in already made SHALL be passed, and Back SHALL return to the page shown before. A refusal SHALL be reported on the card, Continue available again.

#### Scenario: Google
- GIVEN an address hosted at Google, mail, contacts and calendars picked
- WHEN the servers page's Continue is tapped
- THEN Google's consent opens at once, for all three, and "Review your settings" follows

#### Scenario: Fastmail over IMAP, SMTP and DAV
- GIVEN mail at imap.fastmail.com, sending at smtp.fastmail.com, contacts and calendars at their DAV servers, all with an app password
- WHEN the servers page's Continue is tapped
- THEN Mail's page asks a login and password, then Mail (sending)'s, Contacts' and Calendar's pages follow, each with its own fields under *Same login and password as Mail*

#### Scenario: A wrong password
- GIVEN Mail's page with a password the server refuses
- WHEN Continue is tapped
- THEN the card reads "Wrong password", and the page stays

### Requirement: A sign-in reuses an earlier one's login and password
From the second password sign-in on, its card SHALL offer a switch *Same login and password as <domain>*, naming the earlier password sign-in whose server bears the same name, else the first one. The switch SHALL start on when the two servers share their name (imap.fastmail.com and smtp.fastmail.com), off otherwise, so a password never goes to a name it was not typed for unasked. While on, the card SHALL hide its fields and Continue SHALL sign in with that sign-in's login and password; a refusal SHALL turn it off, the fields shown.

#### Scenario: Another provider
- GIVEN mail signed in at imap.mailhost.example, the calendar server typed as dav.example.net
- WHEN Calendar's sign-in page opens
- THEN its switch *Same login and password as Mail* is off and the card asks a login and a password

### Requirement: A browser grant covers the domains of its server
A browser grant SHALL run for every picked domain of the same authorization server and audience through the same client at once, and SHALL take the granted scopes from the token response. A domain whose scope was not granted SHALL be said on the grant's page, and its Continue SHALL ask the provider again.

#### Scenario: Contacts left unticked at Google
- GIVEN a Google consent where the contacts permission was unticked
- WHEN the browser comes back
- THEN Google's page shows, reading that Contacts was not allowed, and Continue opens Google's consent again

### Requirement: The settings are reviewed on one page
After the sign-ins the flow SHALL show "Review your settings": one card as on the servers page, a section per connected domain under hairlines, led by its domain's tile, picked, and its name, holding the rows of the account settings: Mail: *Notify new mail*, *Subscribed mailboxes*; Mail (sending): *Your name*; Contacts: *Show in phone contacts*, *Subscribed addressbooks*; Calendar: *Show in phone calendar*, *Subscribed calendars*. A subscribed row SHALL show its default until opened (all but the junk and the trash for mailboxes, all for the rest), and opening it SHALL list what the signed-in server holds. *Finish* SHALL save the account with these settings, nothing syncing before (the settings come last, so a domain cannot start its sync on its own page), the calendars not picked kept out of the calendar list and its sync (the calendar filter, by each calendar's store id) before the first sync.

#### Scenario: A Google address
- GIVEN an address hosted at Google, all three domains picked
- WHEN Google's consent is given
- THEN "Review your settings" shows Mail, Mail (sending), Contacts and Calendar

#### Scenario: Two calendars of three
- GIVEN the Calendar section's *Subscribed calendars* opened on Work, Home and Holidays, Holidays unticked
- WHEN Finish is tapped
- THEN the first sync fetches Work and Home, and the calendar filter shows Holidays unticked

## MODIFIED Requirements

### Requirement: The standard setup connects with the best sign-in found
The setup SHALL select for each ticked domain, by default, the best-ranked discovered option: at Google and Microsoft, their own APIs (Gmail, Google Calendar, the People API, Graph) signed in through the app's OAuth registration; elsewhere JMAP over IMAP, SMTP, CardDAV and CalDAV, then OAuth over an API token over a password. Mail (sending) SHALL start on the session mail reads with when it submits (JMAP, Gmail, Graph), else on the best submission endpoint discovery found.

#### Scenario: A Google address
- GIVEN an address hosted at Google, custom domain included
- WHEN mail, contacts and calendars are ticked and nothing is changed
- THEN they connect over Gmail, the People API and Google Calendar, through one Google consent

#### Scenario: A JMAP server offering OAuth and a password
- GIVEN an address whose server offers JMAP with OAuth and with a password, and IMAP with a password
- WHEN mail is ticked and nothing is changed
- THEN mail connects over JMAP through OAuth

### Requirement: A connected account is synchronised before the app shows it
Finishing the connection flow SHALL land on the list of the first ticked domain in the bar's order, where the sync dock shows the first syncs still running. A domain whose first sync failed or never started SHALL owe it to the first time its tab is reached, which SHALL sync that domain alone, under the sync dock, and a failed one SHALL stay owed, tried again on the next visit. The user SHALL NOT have to refresh a domain to see what was just connected.

#### Scenario: Contacts and calendars
- GIVEN a flow that ticked contacts and calendars
- WHEN it finishes
- THEN the app lands on the contacts list, whose first sync started at Finish

#### Scenario: One domain fails
- GIVEN a first calendar sync that fails
- WHEN the calendar tab is opened
- THEN its first sync runs again, the domains that succeeded untouched

### Requirement: The setup names sending in the words of the setup it is in
*Mail (sending)* SHALL be a section of the servers page beside Mail, and a page when it signs in on its own. Over JMAP, Gmail or Graph it SHALL submit through the session mail reads with and its page SHALL ask no sign-in; over IMAP it SHALL start on the best submission endpoint discovery found, implicit TLS or STARTTLS, signed in by an SMTP authentication with nothing sent, with mail's credential when the login and password are mail's and a second credential of the account otherwise. A submission endpoint offered with a method and without one SHALL be listed once, with its method. An address publishing none SHALL leave *Mail (sending)* unpicked, reading "Nothing found", picking it opening its empty form; the account SHALL send only once an endpoint is entered.

#### Scenario: An address that publishes submission
- GIVEN an address reading mail over IMAP with a password, whose discovery turned up an implicit-TLS SMTP endpoint
- WHEN mail is ticked
- THEN the Mail (sending) section is picked and says where it sends, naming no protocol
- AND its page offers *Same login and password as Mail*

#### Scenario: An address that publishes none
- GIVEN an address whose discovery turned up no submission this client can drive
- WHEN the flow finishes with Mail (sending) left unpicked
- THEN the account stores no submit endpoint, and its settings can add one

### Requirement: A permission is asked only as its option is turned on
The app SHALL ask the contacts, calendar and notifications permissions only when the user turns on an option needing them: the phone mirror or the notifications switch of the settings page, or the matching option in an account's settings. A refusal SHALL turn that option back off. Nothing else SHALL prompt: not continuing a setup, not opening the app, not a sync. An option whose permission was revoked in the system settings SHALL read as off, and turning it on SHALL ask again.

#### Scenario: Refused in a setup
- GIVEN the settings page of a setup
- WHEN "Show in phone contacts" is turned on and the permission refused
- THEN the switch is off again, and Finish asks nothing

#### Scenario: Revoked later
- GIVEN an account notifying new mail, its notifications permission then revoked in the system settings
- WHEN its settings are opened
- THEN "Notify new mail" reads off, and turning it on asks the permission

### Requirement: The setups offer new-mail notifications
The settings page's Mail section SHALL offer a switch "Notify new mail", off by default, asking the notifications permission on Android 13 and later when it is turned on. Connected with it on, the account SHALL notify new mail and SHALL sync in the background, at the default interval when its interval was off.

#### Scenario: Turned on
- GIVEN the settings page of a setup
- WHEN "Notify new mail" is turned on, the permission granted, and Finish tapped
- THEN the account's settings show "Notify new mail" on and background sync every 15 minutes

#### Scenario: Left off
- GIVEN the same page with the switch left off
- WHEN Finish is tapped
- THEN no permission was asked and the account does not notify

### Requirement: The user sees where a password goes
A sign-in card asking for a password or token SHALL name, before it is sent, every server it signs in to, by host; a server typed by hand is the user's own. A host outside the address's domain is not flagged: providers routinely serve their addresses from another domain.

#### Scenario: Mail hosted elsewhere
- GIVEN `jane@example.org` whose autoconfig names `imaps://imap.fastmail.com:993`
- WHEN its sign-in page shows
- THEN its card names `imap.fastmail.com`, and no other line says where the password goes

### Requirement: An OAuth client is entered on a page
When a grant needs a client the servers page did not name (an issuer that turns out to register none, or a section left on *Auto* at a server with no application of the app's own), the setup SHALL ask for the client on a page of its own (client ID, optional secret, authorization and token endpoints, scope, redirect URI, the discovered ones prefilled), and SHALL return to the domain page with nothing lost. The user's own client is otherwise entered on the servers page (*A card's OAuth application is picked on the servers page*).

#### Scenario: A server without dynamic registration
- GIVEN a domain page whose server refuses dynamic client registration
- WHEN its Continue starts the grant
- THEN the OAuth client page opens with the endpoints prefilled and the client ID empty

## REMOVED Requirements

### Requirement: The setup mode decides what the domain screen asks
Replaced by *The setup has one path*: there is no mode to choose and no domain screen; the domains are picked on the servers page, the custom settings behind each section's settings button.

### Requirement: The standard setup starts with nothing ticked
Replaced by *The servers page says how each domain connects*: a domain starts picked when discovery found it and the account does not have it yet.

### Requirement: The standard setup asks for one password
Replaced by *Each sign-in has a page when it asks something* and *A sign-in reuses an earlier one's login and password*: a password typed once is offered, switched on, to the later domains whose servers share its name.

### Requirement: The advanced setup signs in on one page
Replaced by *Each sign-in has a page when it asks something* and *The settings are reviewed on one page*.
