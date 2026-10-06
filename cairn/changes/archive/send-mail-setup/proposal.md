---
cairn: change
id: send-mail-setup
status: landed
created: 2026-09-11
---

# Set sending up with the account, and put the outbox on pimdir's queue

## Why

Sending was built and never configured. The composer, the RFC 5322 composition, the outbox and the SMTP handover all landed with mail-composition, and the account carries a submit endpoint beside the one it reads from. But nothing on the connection flow ever asks about it: OnboardingFlow.submissionUrl() walks the discovery results for the first `smtp` config whose security is implicit TLS and takes it silently, and an option only picks it up when the config it rides is IMAP.

So sending works exactly when discovery happened to turn up an SMTP record, and there is no way to find out that it did not. An address whose autoconfig is silent, whose only SMTP row is STARTTLS, or that was connected over JMAP, produces an account MessageCompose.open() filters out of the sender list: the compose button answers "no account can send", and nothing in the app or the setup ever named sending, so there is nothing to correct. AccountSettings is addressbook-shaped and offers no repair either, which leaves deleting the account and reconnecting, in the hope that discovery goes differently the second time.

The setup choice is what makes this fixable in the right two ways. The standard setup declined the protocol question, so it should say *send mail* and pick the endpoint itself. The advanced setup asked it, so it should say *SMTP* and show the endpoint like any other configuration, manual entry among them.

The outbox is the other half. It is a collection whose id is a control character (MailStore.OUTBOX), kept alive by hand across every roster replace, and a submission the server refuses stops the drain with nothing recorded but a toast: no attempt count, no error on the row, no parked state, so the next sync tries again identically and forever. pimdir already specified this write door, with a mail submission as its worked example (STORAGE section 15.3), and the vendored statements for it are already compiled into the bridge (`ENQUEUE_ACTION`, `LIST_PENDING_ACTIONS`, `LOAD_PENDING_ACTIONS`, `CLAIM_ACTION`, `PARK_ACTION`, `BUMP_ATTEMPTS`, `CANCEL_ACTION`). The app uses none of them.

## What

**The standard setup says send mail.** Under the mail switch, a second switch named the way the setup names everything else: *send mail*, no protocol on it. On and switched when discovery found somewhere to submit, off and unswitchable when it did not, saying the advanced setup can connect it, which is the shape the domain screen already uses for a domain out of reach. It signs in with the credential mail signed in with, no second prompt: one address, one password is the promise the standard setup makes, and a provider that needs two is a decision, which belongs next door.

**The advanced setup says SMTP.** Under a switched-on mail section, its own rows: one per discovered SMTP configuration, labelled SMTP and detailed with its host, plus manual entry, plus a row for none. Manual entry asks for a host or host:port and builds `smtps://host:port`, defaulting the port to 465, exactly as the mail server row builds an `imaps://` one. Implicit TLS only, for the reason the spec already gives: this client has no STARTTLS step, and driving a `starttls` endpoint as if it were implicit hands a message over in the clear rather than failing.

**An account can gain a sender afterwards.** The account settings screen grows a mail section for an account covering mail: where it submits, with the same rows the advanced setup shows. Without it the fix this change writes is out of reach for every account already connected, which is the population that has the problem.

**The outbox becomes the queue.** A send enqueues one action of the app's own kind, `submit`, versioned `{ "v": 1, "from": …, "object": hash }`, with the composed message written to the blob directory and pinned by the enqueue, in the one transaction section 15.1 prescribes. The mail list overlays the account's pending `submit` actions (section 15.4) where it draws the outbox today. The sync drains them in append order under a claim, hands each to the submit endpoint, files the sent copy, and acknowledges with `cancel_action`, which is what section 15.5 provides for an intent whose effect is not a store mutation. A refusal the server calls permanent parks the row with its error, a refusal of the environment bumps the attempt count, and a parked row is what the list shows as failed to send instead of retrying it forever.

This is the part worth arguing about, so the trade is stated plainly. Today an outbox message is an item: it has a body, a sort key, and the merged descending scan picks it up for free. A queue row has none of those, so the list gains an overlay it does not have today, and that is real work bought with real churn. What it buys back is the bookkeeping the current drain has no place for (attempts, error, parked), the end of the control-character collection and its by-hand survival across roster replaces, and the write door the standard already designed for exactly this. The alternative worth naming is keeping the outbox collection and adding attempt and error columns to it, which is cheaper and is a private schema pimdir already has a public one for.

## What this does not do

**A second credential for submission.** SMTP keeps signing in with the mail connection's credential, which is what the bridge does today (`session.credentials()`). A relay wanting its own login is real, and it costs a second credential per connection through AccountConnection, SecureStore and the bridge's Account, which is a change of its own and not this one.

**JMAP submission.** `EmailSubmission/set` (RFC 8621 section 7) is still refused at composition, so a JMAP account still shows no sender. The wording this change writes applies to it the day that lands.
