---
cairn: spec
capability: onboarding
status: current
---

# Onboarding

The connection flow is one address, the standard setup with the advanced one a link away, and one screen per account. Discovery turns the address into services (the fixed provider rules first, then PACC, CardDAV, CalDAV, autoconfig and JMAP), and the domains those services serve are what the screen offers: mail, contacts and calendars, each a switch carrying its name and the glyph the app's own bar draws it with.

An account is the unit. Whatever the run connected is saved once, as one entry naming every domain it covers, and one browser grant covers every domain that chose the same authorization server for the same API.

### Requirement: The setup mode decides what the domain screen asks
The connection flow SHALL start in the standard setup, without asking how much to ask, and SHALL offer the advanced setup as a link on the address screen and beside the standard setup's password field. The standard setup SHALL offer one row per discovered domain, unticked to begin with, and no configuration; the advanced setup SHALL offer, under each switch, one option per discovered service and authentication method plus manual entry. Two options reading the same, protocol, server and authentication method, SHALL be one: a server taking OAuth through two grants is one OAuth sign-in, and the grant kept is the code grant, a phone having a browser.

#### Scenario: The standard setup
- GIVEN an address whose services are discovered
- WHEN the address is entered
- THEN the domain screen lists each discovered domain, unticked, carrying its name and glyph
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

### Requirement: A connected account is synchronised before the app shows it
Finishing the connection flow SHALL land on the list of the first domain the account covers, mail first, and each domain the account covers SHALL owe its first sync to the first time its tab is reached: that tab SHALL sync that domain alone, under the sync dock. A first sync that failed SHALL stay owed, tried again on the next visit. The user SHALL NOT have to refresh a domain to see what was just connected.

#### Scenario: An account covering three domains
- GIVEN a connection flow that connected mail, contacts and calendars
- WHEN it finishes
- THEN the app lands on the mail list, whose first sync fetches the newest chunk of each mailbox and nothing else
- AND the contacts and the calendars sync, each under the sync dock, the first time their tab is opened

#### Scenario: One domain fails
- GIVEN a first calendar sync that fails
- WHEN the calendar tab is opened again
- THEN its first sync runs again, the domains that succeeded untouched

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
- THEN mail (IMAP, Gmail API), sending (SMTP), contacts (People API) and calendars (Calendar API) are offered

### Requirement: Google connects over its APIs alone
The setups, standard and advanced, SHALL NOT offer Google's CardDAV or CalDAV, whether io-pim-discovery's provider rule names them or not: Google's contacts SHALL connect over the People API and its calendars over the Google Calendar API. The app's Google client SHALL NOT ask for the `https://www.googleapis.com/auth/carddav` scope, nor sign in to Google's CalDAV host.

#### Scenario: A Gmail address in the advanced setup
- GIVEN an address the Google provider rule matches, its services discovered with CardDAV and CalDAV among them
- WHEN the advanced setup lists the ways to connect contacts and calendars
- THEN it lists the People API and the Google Calendar API, and no CardDAV or CalDAV row of Google's

### Requirement: A domain with no autoconfig is asked of its MX provider
When the address's domain publishes no autoconfig, the protocol sweep SHALL ask the provider its MX records name (the registrable domain of each MX host, never a public suffix) for its autoconfig, and SHALL follow an `autoconfig.<domain>` alias to its target when the alias itself fails, over https only.

#### Scenario: A Migadu-hosted domain
- GIVEN `test@migadu.net`, whose only record is an MX at `flatmail.com`
- WHEN discovery runs
- THEN mail (IMAP, `imap.migadu.com:993`) and sending (SMTP, `smtp.migadu.com:465`) are offered

### Requirement: A provider grant is the app's own
A browser grant against Google's or Microsoft's authorization server SHALL run with the app's registered client only when every endpoint the token will be presented to (each domain's base and the submission server) is that provider's own, and SHALL then ask only for that provider's fixed scopes; any other SHALL run as a generic grant. It SHALL NOT send an RFC 8707 resource. Domains SHALL share one grant only when their scopes address the same API.

#### Scenario: Microsoft mail and calendars
- GIVEN mail and calendars both connected through Microsoft
- WHEN the sign-in sequence runs
- THEN it asks for two consents, Outlook's and Graph's

#### Scenario: A configuration pairing Google's sign-in with another host
- GIVEN an autoconfig naming Google's authorization endpoint beside `imaps://imap.evil.example:993`
- WHEN the browser card runs
- THEN the app's Google client is not used, the generic client page opens instead

### Requirement: Each build type signs in to Google with its own client
Release builds, every channel alike (Google Play, the GitHub APKs, F-Droid), SHALL sign in to Google with the client of the verified "Pimalaya" project, and debug builds with the client of the "Pimalaya Debug" project, so the verified project holds only what ships. Each build SHALL carry one Google client ID and its reversed form as the redirect scheme, both set per build type in android/app/build.gradle.kts and read by Oauth.java and the manifest's intent-filter. An account SHALL keep refreshing its tokens with the client it was granted by, so a build changing its client strands no grant.

#### Scenario: An account granted through the former client
- GIVEN a Google account signed in by a release build carrying the "Pimalaya Debug" client
- WHEN a release build carrying the "Pimalaya" client syncs it
- THEN its tokens refresh through the "Pimalaya Debug" client, and the account keeps working without a new sign-in

### Requirement: A debug build installs beside a release build
Debug builds SHALL be the package `org.pimalaya.debug`, named "Pimalaya Debug" and its launcher icon the logo on black, and SHALL share no identifier a second install collides on with release builds: their phone account types, their OAuth redirect schemes (Google's, Microsoft's and the dynamic-registration one) and their launcher shortcut SHALL be their own. Release builds SHALL keep `org.pimalaya` and every identifier they carry today.

#### Scenario: Both builds on one phone
- GIVEN a release build of Pimalaya installed with its accounts
- WHEN a debug build is installed
- THEN both stay installed, each with its own accounts, and a sign-in started in either one returns to the app that started it

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

### Requirement: A locked phone shows only how many messages arrived
A new-mail notification SHALL show on a locked phone only how many messages arrived, whatever the phone's own lock-screen setting, until "Sender and subject on the lock screen" is turned on in the app's Settings page, off by default; turned on, the phone's own setting SHALL decide. Unlocked, the notification SHALL show the sender and the subject either way.

#### Scenario: Off by default
- GIVEN an account notifying new mail and a phone set to show all notification content
- WHEN a message from Ann arrives while the phone is locked
- THEN the lock screen reads "1 new message", with no sender or subject

#### Scenario: Turned on
- GIVEN "Sender and subject on the lock screen" turned on
- WHEN a message arrives while the phone is locked
- THEN the lock screen shows what the phone's own setting allows

### Requirement: The standard setup starts with nothing ticked
The standard setup SHALL open with every domain unticked, so each ticked one shows its options to read in turn, and Continue SHALL stay disabled until one is ticked. Ticks SHALL be kept when the flow steps back to this screen.

#### Scenario: A Microsoft address
- GIVEN an address offering mail, contacts and calendars
- WHEN the standard setup is shown
- THEN no domain is ticked, no option row shows, and Continue is disabled

### Requirement: An in-flight OAuth grant never sits on disk in clear
The app SHALL persist a grant that goes through the browser only sealed under the same Keystore key as the accounts, client secret, CSRF state and PKCE verifier included, and SHALL abort the grant rather than persist it in clear. It SHALL drop the persisted grant once its own redirect arrives (An OAuth redirect is the grant's own), before the code is exchanged, when the grant aborts, when the user leaves or steps back in the connection flow, and once it is 15 minutes old. A grant an older version persisted in clear SHALL be wiped when the app next starts, and one that no longer decrypts SHALL be dropped.

#### Scenario: The process dies in the browser
- GIVEN a Google grant started, the app reclaimed while the user signs in
- WHEN the redirect brings the app back
- THEN the grant is restored from its sealed copy and dropped from disk before the exchange

#### Scenario: The user walks away
- GIVEN a grant persisted for the browser
- WHEN the user closes the connection flow or steps back in it
- THEN the persisted grant is gone, and a late redirect redeems nothing

#### Scenario: An upgrade over a plaintext grant
- GIVEN the plaintext preferences an older version wrote for a grant
- WHEN the app starts
- THEN they are wiped

### Requirement: Setup accepts encrypted servers only
The connection flow SHALL keep a discovered HTTP endpoint only when it is https, SHALL resolve DAV SRV records secure-only, and SHALL fetch an autoconfig named by a `mailconf=` TXT record only over https. A typed mail server SHALL be `imaps` (a bare host gets port 993), a typed DAV URL `https`, a typed submission server `smtps` or `smtp`; anything else SHALL be refused on its card, nothing sent.

#### Scenario: A typed plain IMAP server
- GIVEN the advanced setup with mail entered by hand as `imap://mail.example.org:143`
- WHEN Connect is pressed
- THEN the card says only encrypted servers are accepted, and no password is sent

### Requirement: The user sees where a password goes
A sign-in card asking for a password or token SHALL name, before it is sent, each endpoint it signs in to by scheme and host, and SHALL flag in the error colour an endpoint whose host is outside the address's domain.

#### Scenario: Mail hosted elsewhere
- GIVEN `jane@example.org` whose autoconfig names `imaps://imap.fastmail.com:993`
- WHEN the sign-in card shows
- THEN it reads that the password is sent to `imaps://imap.fastmail.com:993`, not on `example.org`, flagged

### Requirement: OAuth servers are checked
The issuer, every endpoint its metadata names and the authorization URL the browser opens SHALL be https (http only on a loopback host); the metadata's `issuer` SHALL be the issuer asked for (RFC 8414 section 3.3); an issuer-flow redirect whose `iss` names another issuer SHALL be refused before the code is exchanged (RFC 9207). No error SHALL carry the redirect URL, which holds the code.

#### Scenario: A mix-up redirect
- GIVEN a grant started at issuer A
- WHEN the redirect comes back with `iss` naming issuer B
- THEN it is refused and nothing is exchanged

### Requirement: Nothing leaves the phone by backup or transfer
The app SHALL exclude every file from cloud backup and from a device transfer. Accounts the Keystore key cannot open for good (a failed tag, a lost key) SHALL be dropped once and the user asked to add them again; a Keystore failing for now SHALL leave the app starting with no account rather than failing.

#### Scenario: A phone migrated by the maker's tool
- GIVEN accounts sealed on another phone
- WHEN the app starts
- THEN it opens, asks to add the accounts again, and starts every time after

### Requirement: The sync adapters serve their own accounts alone
The contacts sync adapter SHALL run only for one of the app's accounts and the contacts authority, the calendar one only for one of the app's accounts and the calendar authority; any other request, a missing account included, SHALL do nothing.

#### Scenario: Another app asks for a sync
- GIVEN an app calling the calendar adapter for a book's account and the contacts authority
- WHEN the adapter runs
- THEN the book's contacts sync is left as it was

### Requirement: An OAuth redirect is the grant's own
An intent SHALL redeem, and so end, the in-flight grant only when its URL is the grant's redirect URI exactly, up to its query, and its query carries the grant's state; any other intent reaching the app SHALL leave the grant waiting. A grant started more than 15 minutes ago SHALL be abandoned: dropped when a redirect arrives, when it is loaded and when the app starts. A grant over the app's own scheme SHALL show the step's loader while the browser is out.

#### Scenario: Another app fires the redirect scheme
- GIVEN a grant waiting in the browser
- WHEN another app or a link in a message opens `org.pimalaya:/oauth2redirect?code=x&state=forged`
- THEN nothing is redeemed and the real redirect still completes the grant

#### Scenario: A grant left in the browser
- GIVEN a grant started and never finished
- WHEN the app starts 20 minutes later
- THEN the sealed grant is gone

### Requirement: A custom client's loopback waits for its own redirect
The loopback redirect of a custom client SHALL be `http://127.0.0.1` (RFC 8252 section 8.3), a typed `localhost` refused. Its listener SHALL run on a thread of its own, give each connection 10 s and 8 KiB to send its request line, answer 400 to any request but a `GET` of the redirect's path carrying the grant's state, and keep waiting for that one until its 300 s pass.

#### Scenario: A local app connects first
- GIVEN a custom-client grant waiting on its loopback port
- WHEN another app connects and sends nothing, then the browser lands
- THEN the silent connection is dropped after its timeout and the browser's redirect completes the grant, the app's sync never held

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

### Requirement: The advanced setup signs in on one page
The advanced setup SHALL gather every sign-in its selection needs on one page, one card per server and login, and SHALL NOT open a dialog per sign-in. A card SHALL ask what its method needs: a login and a password, an API token, a server with a login and a password for manual entry, or a browser grant. Domains sharing a server and a login SHALL share a card. A password card after the first SHALL offer to reuse the first card's login and password. Connecting SHALL run every card and report each one's outcome on its card, keeping the cards that succeeded.

#### Scenario: Two servers, two passwords
- GIVEN an advanced selection reading mail over IMAP and contacts over CardDAV, on two servers
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

#### Scenario: Manual entry
- GIVEN mail set to manual entry
- WHEN the sign-in page opens
- THEN mail's card asks for a server, a login and a password

### Requirement: An OAuth client is entered on a page
When a server registers no client on its own, or the user asks to use their own, the advanced setup SHALL ask for the client on a page of its own (client ID, optional secret, authorization and token endpoints, scope, redirect URI, the discovered ones prefilled), and SHALL return to the sign-in page with nothing lost.

#### Scenario: A server without dynamic registration
- GIVEN a browser card whose server refuses dynamic client registration
- WHEN its sign-in starts
- THEN the OAuth client page opens with the endpoints prefilled and the client ID empty
