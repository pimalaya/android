---
cairn: change
id: one-connection-per-pass
status: landed
created: 2026-09-08
---

# A pass opens one connection, not one per thing it does

## Why

Marking three messages read and syncing costs four IMAP connections: one for the account walk, and one apiece for the three `UID STORE` commands. Each is a full TCP connect, a TLS handshake and an `AUTHENTICATE` to carry a command that fits in a line. A calendar pass pays the same way: one HTTPS handshake to list the calendars, another per calendar to list its events, another per write.

Nothing decided this. It falls out of where the client object lives:

```rust
pub struct Client<'a, 'local> {
    env: &'a mut Env<'local>,
    transport: &'a JObject<'local>,
}
```

`Env<'local>` is the JNI environment for one native call and `JObject<'local>` a local reference the JVM invalidates when that call returns, so a session borrowing them cannot outlive the call. `PimalayaClient` then opens a `Transport` per verb and closes it in a `finally`, which is the honest thing to do given the above. The module says so in as many words: "Session state is not cached: each native call builds a client, runs one operation, and drops it."

neverest, over the same libraries, does the opposite. `Pool` opens a primary connection per side and keeps it for the whole run; every sequential verb runs on it, a body fetch borrows up to `connections` more and keeps those too, and its `ImapClient` caches the SELECTed mailbox so a run of fetches re-SELECTs once rather than per command. Its client is a long-lived Rust value because there is no JNI boundary to pin it to.

There is no reason Java cannot own the same thing. The Java transport already pools sockets by origin and would reuse them across calls if it were not closed after each one.

## What

A pass opens its connections once and every verb in it runs on them.

**Two seams, because the protocols differ.** An HTTP request is self-contained, so for CalDAV, CardDAV, JMAP and Graph the unit is the socket: the verbs take the `Transport` they run on, and a pass hands the same one to all of them. IMAP is a session, so reusing the socket alone would have the next call replay a greeting the server has already answered and block until the read times out; the unit there is the session.

**The session survives the call it was opened in.** `ImapSession` splits into an owned `ImapState` carrying what outlives a call (the fragmentizer, the URL, the capabilities, and now the selected mailbox) and a per-call view binding that state to a fresh `Client`. `openMailSession` connects and authenticates once and hands Java a handle; `closeMailSession` frees it. Nothing on the Rust side holds a JNI reference between calls, so there is no global ref to register and none to leak: Java owns the transport and passes it back in on every call.

**A dead session is a hint, not a promise.** Server idle timeouts, NAT rebinding and a walk from wifi to cellular all end a connection under the app. A verb that fails on a session the pass was holding reopens it once and runs again, which is the shape the token refresh already has.

**One scope per worker, never one shared.** The contacts push fans out over four threads, and a transport serves one caller at a time. The pool is per worker, as neverest's is: a primary for the sequential verbs and one more per concurrent fetch or push.

What this does not do is hold anything while the app is away. A pass owns its connections and closes them when it ends; keeping them warm for the next manual sync is a policy that sits on top of this and is not part of it.
