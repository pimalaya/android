---
cairn: change
id: mailbox-refusal-skipped
status: active
created: 2026-10-11
---

# A mailbox the server refuses to open is skipped

## Why

The e2e suite (cairn/changes/e2e-suite/tasks.md) saw one mailbox's `EXAMINE` refused fail a whole step: `NO neverest-live-… doesn't exist.` on Exchange, a folder another client made and deleted between the roster and the floor read, and `NO Unknown Mailbox: pimalaya-e2e-…-1 (Failure)` on Gmail, a label made through the API seconds before that a fresh session did not see yet. Neither sends RFC 5530 `[NONEXISTENT]`, and io-imap keeps the text of a tagged `NO` alone, so nothing told such a refusal from a dead connection: the step failed, and no mail of the account's other mailboxes came in.

## What

- **The bridge classifies the refusal.** A `NO` to `EXAMINE` or `SELECT` is, by RFC 3501 6.3.1 and 6.3.2, the server refusing that mailbox (no such mailbox, or no access), the connection standing. `ImapSession::examine` and `select` read the coroutine's outcome apart from the wire's failure (`ImapSession::attempt`) and answer a `NO` with status 404 (`MAILBOX_REFUSED`), the status a Graph folder or a Gmail label gone answers already. A `BAD`, a `BYE`, a closed socket or an auth failure carry no such status.
- **A step skips it.** `ImapStep.opening` leaves a mailbox out of the account floor when `MailEngine.newest` is refused, and out of the listing when `syncSince` is; `WidenStep` widens past a mailbox whose `MailEngine.widen` (its floor read, or its band) is refused, and no longer reports the account held whole then. A first `WidenStep` (Graph, JMAP) skips a mailbox whose first listing is refused.
- **A pass skips it.** `RemotePass.fetchMail` logs such a mailbox as skipped instead of reporting it, and the pass stamps the account as synced.
- **Every mailbox refused is still a failure.** When every mailbox a step reads, or every mailbox of a pass, is refused, the first refusal is reported: Exchange's `NO User is authenticated but not connected` refuses every command while its backend is away, and a step must not read that as an account holding nothing.
- **The roster drops a mailbox really gone.** Nothing new: the next pass's listing replaces the roster (`MailStore.replaceMailboxes`), a mailbox it no longer names leaving with its messages.

## Out of scope

- io-imap keeping the response code of a tagged `NO` (`[NONEXISTENT]`, `[TRYCREATE]`), which would let the bridge tell a missing mailbox from one it may not read.
- A `COPY`, `MOVE` or `APPEND` into a mailbox gone (`NO [TRYCREATE]`): a push, failing as before.
