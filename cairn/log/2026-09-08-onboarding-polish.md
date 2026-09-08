---
cairn: log
change: onboarding-polish
landed: 2026-09-08
---

# Give the standard setup its own screen, and end the flow on a filled app

The connection flow was written for an address book and grew mail and calendars around it. Three edges still read that way, and this is the release pass over them.

## The setup choice now decides the screen

The choice between the recommended setup and the advanced one used to decide two things at the end of the flow (every addressbook subscribed, a fifteen-minute cadence) and nothing at the front, so both answers led to the same screen: a list of protocols and authentication methods under each domain. That screen is the advanced answer to the question the recommended setup exists to decline.

The recommended setup is now three switches, each carrying its domain's name and the glyph the app's own bar draws it with, and nothing else. It answers the configuration question itself, with the password sign-in of the best-ranked configuration found for each domain, and only that one: a password is what a provider documents on its own help page, where a browser grant, an API token or a hand-entered server is a decision, and a decision belongs to the advanced setup. The credential prompt that follows loses the step counter and the protocol name with it, since every step of that setup is the same prompt and the address and server it names are enough to tell two apart.

A domain that offers no password sign-in is switched off for good and says the advanced setup can connect it, and an address that offers none for any domain sends the whole flow there rather than showing three switches that cannot be turned on.

The advanced setup is untouched except for the glyphs, which belong to the switch in both modes. One small thing fell out of splitting the two: manual entry is no longer appended by the option builder but added by the advanced setup, so an empty option list once again means the address offered this domain nothing, which is what the screen's own "nothing was found" line reads and had been unreachable since the manual row started padding every list.

## The first sync covers what was connected

Finishing the flow synced the contacts and nothing else, so an account that had just connected mail and calendars landed on empty lists and waited for the user to find the refresh of each domain, which reads as a setup that did not work. The flow now hands its stored account to one pass that fetches every domain it covers, behind the loader it already showed, and lands on the first of them.

The per-account halves of the mail and calendar refreshes were pulled out for it (`fetchMail`, `fetchCalendars`), so the pass composes the same fetches the domain refresh buttons run rather than a second copy of them. `syncRemote` lost its onboarding flag with its last caller: it was the flag that landed on the contacts list, and where to land is now the first sync's own answer.

## Wording

The welcome page promised contacts in French, and the French half of the flow was missing entirely, so every screen after it came back in English. The onboarding strings are written now, in the app's actual scope, and the two English lines that still described a contacts app (the about dialog, the empty drawer) say what the app is.

Capabilities moved: onboarding (new).
