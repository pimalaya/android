---
cairn: change
id: mail-composition
status: landed
created: 2026-09-03
---

# Let mail be written and sent

## Why

Mail can now be read and acted on, and it still cannot be written. The composer screen has existed as a placeholder since the shell was built, and the connection flow deliberately discovered the SMTP endpoint and threw it away, with a comment saying it belonged there the day sending did. That day is this change.

A domain that cannot answer is not a client. Contacts and calendars both create; mail was the one that could only look.

## What

**A second endpoint on the mail connection.** Mail is the one domain whose server does not answer both ways: IMAP reads and SMTP submits, and they are two hosts. The connection gains a submit URL, discovered alongside the one it reads from and stored beside it. Implicit TLS only, for the reason the read endpoint already gives.

**Composition in the bridge.** The composer's fields become RFC 5322 bytes in Rust: the header folding, the RFC 2047 encoded words a non-ASCII subject or display name needs, and the quoted-printable body. In the bridge because the wire format is the library layer's business and a second implementation of header encoding on the Java side is a second place for it to be wrong.

**Submission over SMTP**, through io-smtp's coroutines and the same Java transport every other protocol uses, followed by an `APPEND` of the copy the sender keeps into the mailbox the server marks `\Sent` (RFC 6154), already read.

**The composer screen**: the sender, the recipients with the copies hidden until asked for, a subject and the text. No draft: nothing is stored until the message is handed over, and leaving says so first.

## What this is not

No attachments, no reply or forward, no drafts, and no JMAP submission: `EmailSubmission/set` (RFC 8621 section 7) creates the message as an `Email` first and names it from the submission, which is a different shape from handing bytes to a server. An account with nowhere to submit is not offered rather than offered and refused.
