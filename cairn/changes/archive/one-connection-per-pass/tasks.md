---
cairn: tasks
id: one-connection-per-pass
---

# Tasks

## The socket seam

- [x] `Transport` becomes public and `AutoCloseable`, and stops being opened per verb.
- [x] Every HTTP verb of `PimalayaClient` takes the `Transport` it runs on.

## The session seam

- [x] `ImapSession` splits into an owned `ImapState` and a per-call view over it.
- [x] `ImapState` caches the SELECTed mailbox, so a run of commands on one mailbox selects once.
- [x] `openMailSession` / `closeMailSession` natives, the handle crossing as a `long`.
- [x] Every mail verb takes the session rather than a URL and a credential.
- [x] `MailSession` on the Java side: the transport, the handle, and reopen-once on a dead connection.

## The passes

- [x] The mail pass opens one session per account and walks, reconciles and pushes on it.
- [x] The calendar pass opens one transport per account and lists and pushes on it.
- [x] The contacts pass opens one per account, and one more per concurrent push or fetch worker.
- [x] A one-off action (open a message, save an entry) opens a scope for itself and closes it.

## Closing

- [x] `:app:assembleDebug` and `:app:testDebugUnitTest` green, `cargo fmt` and `clippy` run.
- [x] Spec folded, log written, CHANGELOG entry added.
