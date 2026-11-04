---
cairn: delta
change: one-onboarding-path
---

## ADDED Requirements

### Requirement: The setup has one path
The connection flow SHALL NOT ask how to set the account up. Once the address is entered and discovered, it SHALL show one screen listing every domain discovery found, in the order of the app's bar, each a checkbox row, the same screen whatever the provider.

#### Scenario: A Fastmail address
- GIVEN an address whose discovery found mail, contacts and calendars
- WHEN discovery ends
- THEN the domain screen lists the three as checkbox rows
- AND no question about a standard or an advanced setup was asked

### Requirement: A ticked domain says how it connects in a sentence
Under a ticked domain, the domain screen SHALL show one row naming in plain words how that domain connects, the best-ranked discovered option selected, and SHALL name no protocol, host or port on it. *More* SHALL unfold the other discovered options of that domain, one radio row each worded the same way, and picking one SHALL select it and fold the list back.

#### Scenario: A JMAP server offering OAuth and a password
- GIVEN an address whose server offers JMAP with OAuth and with a password, and IMAP with a password
- WHEN mail is ticked
- THEN its row reads that it signs in through the browser
- AND *More* lists the password sign-ins as alternatives

### Requirement: One form edits a domain's settings
The unfolded options SHALL end with *Edit settings*, opening one form of protocol, host, port, security and sign-in method, prefilled from the selected option. Saving it SHALL make it the domain's selected option. A domain discovery found no option for SHALL still be listed, and its row SHALL open the same form, empty. The form SHALL refuse a plain-text server, as the setup does.

#### Scenario: Changing a discovered port
- GIVEN mail selected over IMAP on port 993
- WHEN the form is opened from *Edit settings* and the port changed
- THEN the form showed the selected option's protocol, host, port, security and method
- AND the saved settings are mail's selected option

#### Scenario: Nothing discovered for calendars
- GIVEN an address whose discovery found no calendar service
- WHEN calendars is ticked
- THEN its row opens the form, empty

### Requirement: Each ticked domain has a page of its own options
After the sign-ins, the flow SHALL show one page per ticked domain, in the bar's order, built from the same rows as that domain's sections in the account settings. Mail's page SHALL offer new-mail notifications and the download window; the contacts and calendars pages SHALL offer their collections and the phone mirror. A domain holding a single collection SHALL show no collection selection, that collection taken.

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
The setup SHALL gather every sign-in its selection needs on one page, one card per server and login, and SHALL NOT open a dialog per sign-in. A card SHALL ask what its method needs: a login and a password, an API token, or a browser grant; a server set through the settings form SHALL be signed in the same way, the form having set the server. Domains sharing a server and a login SHALL share a card, and every card SHALL name the domains it signs in for. A password card after the first SHALL offer to reuse the first card's login and password. Connecting SHALL run every card and report each one's outcome on its card, keeping the cards that succeeded.

#### Scenario: Two servers, two passwords
- GIVEN a selection reading mail over IMAP and contacts over CardDAV, on two servers
- WHEN the sign-in page opens
- THEN it shows one card per server, each with a login and a password
- AND no dialog opens

#### Scenario: Reusing a password
- GIVEN the same page
- WHEN the second card's reuse box is ticked
- THEN its fields hide and it signs in with the first card's login and password

#### Scenario: One card refused
- GIVEN the same page and a password the contacts server refuses
- WHEN Connect is tapped
- THEN the contacts card says the password was not accepted
- AND mail stays connected

## MODIFIED Requirements
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
Mail's card SHALL carry sending as a *Send mail* checkbox, ticked when discovery found a submission endpoint this client can drive, with a sentence row naming where it sends, *More* listing the other discovered endpoints and *Edit settings* opening the form. An address publishing none SHALL show it unticked, its row opening the form empty. Implicit TLS and STARTTLS are both driven. Submission SHALL sign in with the credential the mail connection signed in with, and the flow SHALL NOT prompt a second time. An endpoint offered with a method and without one SHALL be listed once, with its method.

#### Scenario: An address that publishes submission
- GIVEN an address whose discovery turned up an implicit-TLS SMTP endpoint
- WHEN mail is ticked
- THEN *Send mail* is ticked under it, its row naming where it sends
- AND no protocol is named on the row

#### Scenario: An address that publishes none
- GIVEN an address whose discovery turned up no submission this client can drive
- WHEN mail is ticked
- THEN *Send mail* is unticked and its row opens the form, empty

#### Scenario: Sending unticked
- GIVEN an address that publishes submission
- WHEN the flow finishes with *Send mail* unticked
- THEN the account stores no submit endpoint, and is not offered as a sender

#### Scenario: Sending ticked
- GIVEN an address that publishes submission
- WHEN the flow finishes with *Send mail* ticked
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
Replaced by *The setup signs in on one page*: a password typed once for several domains is the reuse box of the sign-in page.

### Requirement: The advanced setup signs in on one page
Replaced by *The setup signs in on one page*, which every setup runs.
