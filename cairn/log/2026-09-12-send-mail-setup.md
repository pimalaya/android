---
cairn: log
change: send-mail-setup
landed: 2026-09-12
---

# Sending is set up with the account, and the outbox is pimdir's queue

Sending was built and never configured. Everything after the composer worked, and nothing before it asked: the connection flow walked the discovery results for the first implicit-TLS SMTP record and took it silently, and only an option reading over IMAP picked it up. So an account could send exactly when discovery happened to publish a record this client could drive, and when it did not there was nowhere in the app that said so. The compose button answered "no account can send", the setup had never named sending, and the account settings screen is addressbook-shaped, which left deleting the account and reconnecting.

**The standard setup says send mail.** A second switch under the mail switch, carrying no protocol, on when discovery found somewhere to submit and off and unswitchable when it did not, saying the advanced setup can connect it. That is the shape the domain screen already uses for a domain out of reach, and it is a shape rather than an error because reading mail still works. It signs in with the credential mail signed in with: one address, one password is the promise the standard setup makes.

**The advanced setup says SMTP.** Its own rows under a switched-on mail section: one per discovered endpoint labelled with its host, a row for not sending at all, and manual entry, which asks for a host and builds `smtps://host:465` the way the mail server row builds an `imaps://` one. A section reading over JMAP shows none of it: RFC 8621 submits through the session it reads from, so an SMTP server under it would be a second account for something the first one already does.

**An account can gain a sender afterwards.** The settings screen shows where an account submits and takes a new one, leaving the credential and the read endpoint alone. Without it the repair this change writes would have been out of reach for exactly the accounts that have the problem.

**The outbox is the queue now.** It was a collection whose id is a control character, held back by hand from every roster replace, holding items nothing would ever sync. A send now appends one action of the app's own `submit` kind, the composed message written to the blob directory and pinned by the enqueue in the one transaction STORAGE section 15.1 prescribes for a producer. The list overlays those rows above what the store synced, reading the subject and the date off the payload, which is why the payload carries them: a waiting message has no summary row, and parsing a message to draw a list is what the sort key exists to avoid.

The drain is the shape section 15.5 describes for an intent whose effect is not a store mutation. The row is never claimed: a claim deletes it, and a claim before the server accepted the message would lose the message. It is handed over first and cancelled after, which makes submission at-least-once and says so.

What the queue bought is the bookkeeping the old drain had nowhere to put. A 5yz reply is the server's last word on a message (RFC 5321 section 4.2.1), so the row is parked carrying what the server said, shown as refused, and the drain carries on to the rows behind it; anything else counts an attempt and stops the drain where it is, the usual cause being no network. The bridge is what can tell those apart, so `client::smtp::send` now returns a typed `SmtpSendError` instead of a string, and the submission reply carries a `permanent` flag that reaches Java as `SubmissionRefused`. A copy that could not be filed is no longer a reason to run anything again: the message has gone, and sending it twice to file a record of it is worse than the missing record.

Capabilities moved: **onboarding** gains the two requirements about naming sending and repairing it later; **mail** has its submission endpoint, its outbox and its submission requirements replaced.

Built and unit-tested only, as this repository's bar is: `:app:assembleDebug` and `:app:testDebugUnitTest` green, `cargo test --lib` green. Nothing was driven against a server.
