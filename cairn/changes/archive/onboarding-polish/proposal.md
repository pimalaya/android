---
cairn: change
id: onboarding-polish
status: landed
created: 2026-09-08
---

# Make onboarding a PIM onboarding, and end it on a synchronised app

## Why

The connection flow was written for an address book and grew mail and calendars around it. Three of its edges still read that way.

The welcome page still promises contacts in French ("Tous vos contacts de tous vos comptes"), and the French half of the flow is otherwise missing entirely, so the screens after it come back in English.

The setup choice offers a standard and an advanced way in, and then shows the same screen to both: a list of protocols and authentication methods per domain. That screen is the advanced answer. Someone who chose the standard way is being asked which of CardDAV, JMAP and OAuth 2.0 their address serves, which is the question they declined.

And a finished setup lands on an empty app. The first sync runs for contacts alone, so an account that connected mail and calendars shows three blank lists until the user finds the refresh of each domain, which reads as a setup that did not work.

## What

**The welcome page speaks for the app it is in.** Mail, contacts and calendars, offline, in both languages, and the French onboarding strings the flow was missing are written.

**The standard setup is switches.** Three domains, each with its glyph, each on or off, and nothing else on the screen: the configuration is the app's to pick. It picks the login and password sign-in, and only that one. A password is the credential every provider explains on its own help page, so it is the one the standard setup can be sure of; a browser grant, an API token or a hand-entered server is a decision, and a decision belongs to the advanced setup. A domain whose address offers no password sign-in is shown switched off and out of reach, saying which setup can connect it, and an address that offers none at all sends the whole flow to the advanced setup rather than leaving it with nothing to switch on.

The credential prompt that follows asks for a login and a password under a plain title, without the protocol and step counter the advanced sequence needs.

**The advanced setup keeps its options, and gains the glyphs.** The domain switch is the heading of its section either way, so it carries the domain's icon in both modes.

**The first sync covers what was connected.** Every domain the new account covers is synchronised in one pass behind the setup's own loader, and the app lands on the first of them rather than always on the contacts.
