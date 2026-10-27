---
cairn: tasks
change: a-message-is-filed-somewhere
---

- [ ] rust/src/mail: a non-empty `Memberships`, built where JMAP `mailboxIds`, Gmail labels and Graph's parent folder are mapped to names; an unmappable item reported as a skip
- [ ] rust/src/mail/session.rs: `NamedStepItem` and `GraphStepItem` carry it; the reply counts skips
- [ ] GraphStep.java, GmailStep.java: no silent drop; an unresolved name reported
- [ ] Tests: a Graph item in an unnamed folder, a Gmail item under an unknown label, a JMAP item filed only in the trash of an account step
- [ ] Spec: mail.md; fold, log, CHANGELOG
