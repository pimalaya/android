---
cairn: log
change: write-once-and-oauth-dos
landed: 2026-10-11
---

# Writes land once, and a stray intent cannot kill a grant

Capabilities moved: mail (new: a send that may have gone waits for the user, an IMAP copy or append is not made twice; modified: a message is submitted and a copy is kept, an HTTP sync rides out throttling, a dead connection is dropped), calendar (new: a Graph event is created once), onboarding (new: an OAuth redirect is the grant's own, a custom client's loopback waits for its own redirect; modified: an in-flight OAuth grant never sits on disk in clear).

The audit's S2 M6 and L1, S1 L2 and the OAuth carry-over items 2 to 4. A submission failing after the whole message was handed over (SMTP past the final `.`, an HTTP send written whole with no byte of answer or answered 500, 502, 503 or 504, tracked by `Exchange.heard` and `Client::unanswered`) is answered `uncertain` by the bridge, thrown as `SubmissionUncertain`, parked by the drain under `MailStore.UNCERTAIN` and shown *May have been sent*; the reader's *Send again* queues it anew (`MailStore.resendOutgoing`). A failed SMTP `QUIT` after acceptance no longer fails the send. The IMAP no-MOVE relocation and the sent-copy append search the target by `Message-ID` first (`ImapSession::copied`, `holds`). Graph creates carry a `transactionId` from the object's digest (`create_body`). The throttle never resends after a read timeout and retries a 503 only for idempotent methods. `OauthFlow.handleRedirect` redeems only its exact redirect URI with its state (`isRedirectOf`), grants expire after 15 minutes (`PendingGrantStore.SAVED_AT`), `launchSchemeGrant` shows the loader, and the loopback listener (`LoopbackRedirect`) runs on its own thread with a per-connection timeout, a bounded request line and path and state checks, `127.0.0.1` only.
