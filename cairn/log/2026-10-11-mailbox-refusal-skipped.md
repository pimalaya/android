---
cairn: log
change: mailbox-refusal-skipped
landed: 2026-10-11
---

# A mailbox the server refuses to open is skipped

Capabilities moved: mail (new: a step and a pass skip a mailbox the server refuses to open; modified: an account's mailboxes sync side by side).

The e2e suite saw one refused `EXAMINE` fail a whole step, on Exchange (`NO ... doesn't exist.`, a folder deleted since the roster) and Gmail (`NO Unknown Mailbox`, a label a fresh session did not see yet). The bridge's `ImapSession::examine` and `select` now read the coroutine's outcome through `attempt` and answer a `NO` with status 404 (`MAILBOX_REFUSED`), a `BAD`, a `BYE` or a dead wire carrying none. `MailEngine.refused` reads a 404 as a refused mailbox and `MailEngine.skip` skips it: `ImapStep.opening` leaves it out of the account floor and of the listing, `WidenStep` widens past it without reporting the account whole, and `RemotePass.fetchMail` no longer reports it. Every mailbox refused still fails the step or the pass. The next pass's roster drops a mailbox really gone, as it did.
