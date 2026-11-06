---
cairn: log
change: sent-copy-takes-provider-markers
date: 2026-11-06
---

# A sent copy takes the markers of the provider's own

A fix found by the e2e suite (e2e-suite Findings) and folded without a proposal.

Capabilities moved: mail (modified: a sent message is in Sent at once, the staged copy taking the markers of the copy Gmail or Graph files before it lands).

The staged Sent copy carries `\Seen`, a guess made when queueing. Landing it on Gmail's own copy kept that guess as a local edit, so the sync pushed `\Seen`, and Gmail, whose markers are the message's in every label, marked a message sent to oneself read in the inbox too. `MailEngine.nameByMessageId` now stages the arrival's markers on the pending create before naming it, so the landing finds nothing to push. A copy sent to oneself shows unread in Sent too once listed, which is Gmail's own state; until the listing, the staged copy shows read. Test: MailMoveTest `aSentCopyTakesTheFlagsOfTheProvidersOwnCopy`.
