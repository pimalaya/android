---
cairn: change
id: a-message-is-filed-somewhere
status: active
created: 2026-10-18
---

# A stepped message is filed somewhere, by construction

## Why

Borrowed from an external engine: a message's mailbox or label set is a type that cannot be empty, so "a message filed nowhere" is not a value code can hold.

Re-verified on 2026-11-19. Only Gmail steps by account now (`MailStep.of`, `MailStepKind.ACCOUNT`); JMAP and Graph step from the store through `WidenStep` (`Backend::mail_step`, rust/src/account.rs). The Graph step path is dead, and so is the JMAP one (`jmap_step`, `JmapCalls::step`), reached only from tests: `GraphStepItem`, `MailStepReply::Graph` (rust/src/mail/session.rs), `MailClient.graphStep` and `GraphStepReply` are reached only from a test.

A Named step item carries `mailboxes` as a plain `Vec<String>` across JNI, and GmailStep.java (around line 70) files it wherever the names resolve to a collection, skipping the others one by one with nothing said; its body file is still handed to `engine.fetched`.

- Gmail: the item always lands in the hidden All Mail, so it is never lost, but a label the roster does not know goes unreported.
- JMAP: the bridge's account step could file an item nowhere (a mapping bug, a mailbox renamed mid-step), but no app flow reaches it.

## What

- Delete the dead Graph step path (Rust reply variant, the bridge's keyset walk and its tests, client classes, the test stub).
- Delete the dead JMAP step path (`jmap_step` in rust/src/mail/session.rs, `Jmap::step`, `JmapCalls::step` and what only it reaches in rust/src/mail/jmap_step.rs and rust/src/mail/jmap.rs, their tests): the account step is Gmail-only, and its docs say so. The bridge's step request keeps `count` alone; Java's `StepRequest` drops the unused `bound`.
- A `Memberships` type in the bridge, non-empty by construction (a first mailbox plus the rest), built once per item where Gmail labels are mapped to names, with All Mail always among them: today's rule written into the type, so a stepped message cannot be filed nowhere and needs no skip.
- Java reports a mailbox name the engine cannot resolve to a collection the store holds (logged and counted), never skips it silently.
