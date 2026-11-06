---
cairn: change
id: one-onboarding-path
status: active
created: 2026-11-04
---

# One onboarding path

## Why

The connection flow forks right after the address: the standard setup (a tick per domain, nothing technical) or the advanced one (a radio list of configurations per domain, manual entry last). The fork is asked before the user has seen what was discovered, so they choose blind, and the two branches do not look alike. Discovery runs while the user waits on the address. Options that apply to a domain (notifications, the phone mirror, which addressbooks) are scattered between the domain screen and a later step, and nothing syncs until the flow ends and a tab is opened.

The first version of this change (2026-11-04) put the alternatives and the custom settings behind a tune menu on one shared sign-in page, where picking an alternative moved a domain from card to card. It was reworked with the owner on 2026-11-05 on the Pimalaya Android design canvas, rows "Onboarding, one path"; the frames are copied in designs/. After the first device test the same day, the owner asked for plain settings items ("JMAP · OAuth 2.0"), the fields' names inside them, the OAuth client on the servers page rather than behind a link on the domain page, and the options back on one final page grouped by domain, as first proposed. After the second test, the owner folded the domain selection into the servers page (a checkbox per section, what discovery found ticked, discovery back on the address's Continue) and asked for *Auto* in the settings, an *Application* menu for OAuth (Pimalaya or Custom, Custom showing the client), *Auth* for "Sign in with" and a *Security* menu for sending. The checkbox before the glyph read poorly: the owner picked, on the canvas row "Set up your domains", the domain's tile as its tick, the page renamed after what it now does, and asked that no section appear or disappear as others are picked. After the third test, the owner renamed it "Review your domains" and found the page per domain pointless where one identity covers several domains, OAuth above all: the domains are grouped by sign-in, and a lone browser grant opens the browser from the servers page's Continue, a page shown only when there are several sign-ins or one asks something; and asked for subscribed calendars, which the open point below wrongly held to need a per-calendar sync switch first. After the fourth test: Mail and Mail (sending) over IMAP and SMTP may take different passwords (Fastmail's app passwords), so a password is asked per domain again, the same-login switch on for servers of one name; the settings page drawn as the domains page; and, the settings coming last, no domain syncs before Finish, the step that synced each domain on its own page dropped. The *Plain* security asked beside STARTTLS and SSL/TLS is left out: the app has no cleartext SMTP (`smtp://` is STARTTLS) and *Setup accepts encrypted servers only* refuses it, a password then crossing the network in clear. The frames still show the earlier steps.

## What

One path for everyone: the address, the domains, a page per sign-in that asks something, then the settings.

### 1. The address

Continue runs discovery, as the app's buttons wait: the pill dims, keeps its label and turns its spinner at its end, the step inert. A discovery that failed outright (no network) is reported, and Continue tries again.

### 2. Review your domains

One card, a section per domain under hairlines, always the four: Mail, Mail (sending), Contacts, Calendar. Each section leads with its domain's glyph on a tile that is its tick, filled with the accent and badged with a check while picked, then its name over how it connects in a sentence ("With your password, at example.org", "With your Google account, in the browser"), "Connected" for a domain the account has, "Nothing found" for one discovery missed. What discovery found starts picked; Continue waits for one. Mail (sending) greys out while Mail is not picked. A section's settings button, disabled while it is not picked, lists *Auto* (back to what discovery ranked first), every option discovery found for it (owner, 2026-11-05: discovery finds several and picks one, the others have to be seen) as "JMAP · OAuth 2.0", the server named only where two items would read the same, then *Custom settings*; nothing is marked. Picking an option fills the card's form from it: a server to type for IMAP, SMTP, CardDAV and CalDAV; for a JMAP session or a provider's API, its server said but not editable, its sign-in among the ones found for that server. *Custom settings* is the form itself, an edit by hand making it custom. Each field carries its name inside it, before its value ("Server  imap.example.org"). Sending picks its *Security*, STARTTLS or SSL/TLS, in a menu. The form's *Auth* is a password, an API token or OAuth, picked in a menu, OAuth offered for sending only beside mail signed in through the browser. A reading form on OAuth adds an *Application* menu: *Pimalaya*, the app's own (its registration at Google and Microsoft, one registered at an issuer), where the server has one, else *Custom*, which shows the user's client, the server's endpoints or issuer prefilled and the client ID to enter. A domain discovery found nothing for opens, once picked, on the form, empty, under the line "Nothing could be discovered for example.org. Set it up manually." Mail (sending) unpicked does not send; a sender can be added from the account settings later (An account can gain a sender after it is connected).

### 3. A page per sign-in, then the settings

The domains are grouped by identity: one browser grant for the domains of one authorization server, audience and client; a login and password (or a token) per domain, mail's and sending's apart, as a provider may give each service its own app password; sending through mail's sign-in with mail. A lone browser grant asks nothing but a trip to the browser, so the domains page's Continue opens it at once (owner, 2026-11-05: "OAuth just redirects"). Otherwise each sign-in has a page, a browser grant's titled with its provider over the domains it covers, a password's with its domain, holding the sign-in card: the host the password goes to, how it signs in, and what that needs. There is no sign-in button: Continue signs in at every server of the sign-in and opens the next page asking something. A refusal shows on the card ("Wrong password").

After the last, "Review your settings": one card as the domains page, a section per domain led by its tile, picked, holding the rows of the account settings, and Finish.

- From the second password sign-in on, a switch *Same login and password as Mail* (or the earlier domain it names) reuses that one's login and password, on when the servers share their name (imap.fastmail.com and smtp.fastmail.com), off otherwise, so a password never goes to a name it was not typed for unasked.
- A browser grant covers every picked domain of the same authorization server and audience through the same client. A domain whose scope was left unticked on the provider's page is said on the grant's page, whose Continue asks the provider again.
- Settings: Mail: *Notify new mail*, *Subscribed mailboxes* (all but the junk and the trash); no *Sync period*, dropped by the owner on 2026-11-06: a fresh account keeps all of its mail, the first pass lists the newest and *Load more* the rest, the bound staying in the account settings to cap the fill. Mail (sending): *Your name*. Contacts: *Show in phone contacts*, *Subscribed addressbooks*. Calendar: *Show in phone calendar*, *Subscribed calendars*. Every domain is signed in by then, so a subscribed row opens its list at once. The calendars not picked are kept out of the calendar filter at Finish, which is also what the calendar sync takes: a calendar's store id is the account's id and its URL, known before the first sync lists it.

### 4. The account

Saved at Finish, and synced from there: the settings come last, so a domain cannot start its first sync on its own page, nor does anything sync before (owner, 2026-11-05). Finish lands on the first picked domain in the bar's order, the sync dock showing what still runs.

## Open points

- **Parallel first syncs** (from the first version): dropped by the owner on 2026-11-05, the settings coming last; `SyncCoordinator` runs one sync at a time, a first sync only for the tab on screen, mail's on Wi-Fi only.
- **Fastmail calendars over OAuth** still fail with `invalid_target` on the CalDAV shard (one-fastmail-consent), unchanged by this flow.
- **Palette**: the designs carry the brand palette and serif titles proposed beside this change on 2026-11-05; they are a change of their own, not this one.

## Steps

Landed one at a time, each installed and checked on the device before the next:

1. The address runs discovery on Continue, its loader turning.
2. Review your domains: a section per domain led by its tile, what discovery found picked, the sentences, Mail (sending) beside Mail, the settings with Auto, the forms with their Security, Auth and Application menus, the nothing-found line.
3. The sign-in pages, one per identity, a lone browser grant without one: the sign-in card, Continue validating, the refusal on the card, the same-login switch, browser grants on Continue, an unticked scope asked again; the settings page after the last, subscribed calendars included.
4. ~~The account saved at the first validated page, a first sync per page, back-out and launch removal~~, dropped: saved and synced at Finish, landing on the first domain.
5. The subscribed rows signing in on tap; the shared sign-in page, the tune menu, the setup mode and their strings removed; fold, log, CHANGELOG.
