---
cairn: log
change: mailbox-roster-on-demand
landed: 2026-10-07
---

# A session reads the mailbox roster on demand

Capabilities moved: mail (modified: the mail backends' introduction; The merged list reaches down to its mailboxes' floor).

On the owner's device (Microsoft 365), scrolling to the end of the list said older mail needs the network while the phone was online, and the background fill stopped at its first step: ``No mailbox named `Archive` `` and ``No mailbox named `Inbox` ``. Graph, Gmail and JMAP address a mailbox by the id the roster names, and the bridge filled that map only when the session listed the mailboxes, which a pass does and a session opened to widen a mailbox or by the fill never did. A session reopened after its connection died lost it the same way.

**Bridge.** `MailListing::resolve` answers a mailbox's id and reads the roster first when the session holds none for it, so `mailFloor`, `enumerateMailbox` and `nameMessages` work on any session, whoever opened it; `listMailboxes` and the lookup share `http_roster` and `MailListing::remember`. A name the roster just read does not hold is still refused as before.

**App.** The list's last row says older mail needs the network only when there is none; any other failure says older mail could not be loaded, a tap trying again (`MailList.stallOf`, English and French). The failure is still logged.

**Tests.** `MailListing::resolve` on a fresh session (the roster read once and kept, a missing mailbox refused, one made since found by reading it again, a failed roster failing the lookup); over the fake Graph, a widening and a fill step on a session that never listed the folders. Gmail and JMAP have no listing fake; the lookup is the same code for the three. `MailOlderTest` for the row's message.
