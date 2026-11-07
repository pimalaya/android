---
cairn: tasks
change: oauth-hardening
---

# Tasks

- [x] L4 identity check after a browser grant, built in sign-in-again (2026-11-07): Google (Gmail profile, else the primary calendar), Graph `/me`, JMAP session; not read for Google contacts alone, Outlook IMAP and SMTP, Graph without `User.Read`, generic OIDC (io-oauth drops the `id_token`); aliases refused (sign-in-again, Open)
- [ ] L5 `state` checked before an error redirect; the loopback listener keeps listening past a request without the expected path and `state`; tests
- [ ] M1 `iss` required where the metadata advertises it, the field read from the fetched metadata; tests
- [ ] L3 RFC 8707 `resource` on the token exchange and the refresh, where the provider accepts it (Fastmail's `invalid_target` trap, fastmail-jmap-quirks)
- [ ] H3 RFC 9728 protected-resource metadata read where published, else issuer and server on one registrable domain
- [ ] M2 io-oauth refuses http endpoints (library change, released, the bridge following it)
- [ ] Fold into spec/network-trust.md and onboarding.md, log, CHANGELOG
- [ ] network-trust-hardening's Open list pointed here
