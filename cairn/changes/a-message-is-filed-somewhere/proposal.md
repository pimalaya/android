---
cairn: change
id: a-message-is-filed-somewhere
status: draft
created: 2026-10-18
---

# A stepped message is filed somewhere, by construction

## Why

Borrowed from an external engine: a message's mailbox or label set is a type that cannot be empty, so "a message filed nowhere" is not a value code can hold.

Here a step item carries `mailboxes` as a plain list across JNI (rust/src/mail/session.rs: `NamedStepItem::mailboxes: Vec<String>`, `GraphStepItem::mailboxes: Vec<Option<String>>`), and the Java side files it wherever the names resolve, re-verified on 2026-10-18:

- GraphStep.java (around line 68): an item whose list is empty, or whose first name is null (a folder the step does not name), is filed in no collection and nothing says so; its body file, when one was written, is still handed to `engine.fetched`.
- GmailStep.java (around line 70): names that resolve to no collection are skipped one by one; the item still lands in the hidden All Mail, so it is never lost, but a label the roster does not know yet goes unreported.

An empty list is also the shape a bug in a backend's mapping would take (a role filter, a renamed folder), and it fails silently.

## What

- A `Memberships` type in the bridge, non-empty by construction (a first mailbox plus the rest), built once per item where the backend's own ids are mapped to names; an item that maps to none is not a step item but a reported skip, counted in the step's reply.
- Gmail's account-wide step builds its memberships with All Mail always among them, which is today's rule written into the type.
- Java reads a reply in which every item names at least one mailbox, and a mailbox name the engine cannot resolve to a collection is reported, not skipped.

## Open questions

- Whether a skip is an error of the step (stop it) or a count the sync strip shows.
- Graph's single parent folder: a `Memberships` of one, or a type of its own.
