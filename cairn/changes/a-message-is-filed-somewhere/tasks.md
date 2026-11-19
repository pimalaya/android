---
cairn: tasks
change: a-message-is-filed-somewhere
---

- [x] Dead Graph step path removed: `MailStepReply::Graph`, `GraphStepItem`, the Graph step in rust/src/mail/session.rs, the keyset walk in rust/src/mail/msgraph.rs and its tests, `MailClient.graphStep`, `GraphStepReply`, the StepRequestTest stub
- [x] Dead JMAP step path removed: `jmap_step` in rust/src/mail/session.rs, `Jmap::step`, `JmapCalls::step`, `step_query`, `email_state` and the step types in rust/src/mail/jmap_step.rs, their tests, the `holding` fixture; `MailStepRequest` keeps `count` alone
- [x] Java `StepRequest` drops `bound`; the pinned ASKED lines in StepRequestTest rewritten
- [x] rust/src/mail/session.rs: a non-empty `Memberships`, built where Gmail labels are mapped to names, All Mail always among them; `NamedStepItem` carries it
- [x] GmailStep.java: an unresolved name logged and counted, no silent drop
- [x] Tests (failing first): a Gmail item under an unknown label, a mailbox name the store does not hold
- [x] delta.md
- [ ] spec/mail.md folded, log, CHANGELOG (after the owner checks the result)
