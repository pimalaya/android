---
cairn: log
change: one-connection-per-pass
landed: 2026-09-08
---

# A pass opens one connection, not one per thing it does

Marking three messages read and syncing cost four IMAP connections: one for the account walk, and one apiece for the three `UID STORE` commands. Each was a TCP connect, a TLS handshake and an `AUTHENTICATE` to carry a command that fits on a line. A calendar pass paid the same way, once to list the calendars and again per calendar and per write.

**Nothing decided this; it fell out of where the client object lived.** `Client<'a, 'local>` holds `&mut Env<'local>` and a `&JObject<'local>`, and both belong to one native call: the environment is that call's, and the object is a local reference the JVM invalidates on return. A session borrowing them could not outlive the call, so `PimalayaClient` opened a transport per verb and closed it after, which given the above was the honest thing to do. neverest, over the same libraries, keeps a `Pool` open for a whole run and re-selects a mailbox once rather than per command, because its client is a long-lived Rust value with no JNI boundary to pin it to.

**The session now outlives the call.** `ImapSession` split into an owned `ImapState`, holding what a conversation carries between commands (the fragmentizer, the endpoint, the capabilities and the selected mailbox), and a per-call view binding that state to a fresh client. `openMailSession` connects and authenticates once and hands Java a handle; `closeMailSession` frees it. Nothing on the bridge side holds a JNI reference between calls, so there is no global reference to register and none to leak: Java owns the transport and passes it back in, which is also why the credential moved onto the session and stopped crossing on every verb.

**The socket seam was the easy half.** An HTTP request carries its own state, so for CalDAV, CardDAV, JMAP and Graph nothing had to survive but the socket, and the transport already pooled those by origin; it was only being closed too early. Every verb now takes the transport it runs on, a pass hands one to all of them, and the one-off callers that are genuinely one call open one for themselves.

**A fan-out gets one connection per worker, not per task.** The contacts push runs over four threads, and a transport serves one caller at a time. The round now submits four workers that drain a shared queue, each owning its own transport, which is neverest's shape and turns a forty-card round from forty connections into four.

**A held connection is a hint.** Server idle timeouts, a rebound NAT and a walk from wifi to cellular all end one under the app without saying so, so a verb that fails on a held session reopens it and runs once more before its failure is reported as its own. That retry is for idempotent verbs only, and the exception is the important part: submitting a message is not retried, because a submission that was accepted and then failed to file its `\Sent` copy looks exactly like one that never went, and running it again would send the message twice. It stays in the outbox instead, which is what the outbox is for.

**A mailbox is selected once.** The session records which mailbox is open and how, since an `EXAMINE` and a `SELECT` are not interchangeable and a listing must not clear anyone's `\Recent`. A run of commands on one mailbox then opens it once. The record is written before the command rather than after, so a select that failed part-way leaves the connection on no mailbox rather than claiming the old one.

**What moved with it.** The mail token refresh moved from around each verb to where the session is opened, which is the honest place: a session is a sign-in, so a token that expired is a connection that cannot be opened rather than a verb that has to be retried. One consequence worth stating: a token expiring in the middle of a pass now fails that pass instead of renewing under it. An access token lives about an hour and a pass takes seconds, and the next sync opens with a fresh one.

**What this does not do** is hold anything while the app is away. A pass owns its connections and closes them when it ends. Keeping one warm for the next manual sync is a policy that sits on top of this, and it is not here.

Capabilities moved: offline-store.
