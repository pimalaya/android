---
cairn: tasks
change: one-fastmail-consent
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

Parked 2026-11-19 by the owner: two consents accepted until JMAP calendars work.

- [-] Live check of several `resource` parameters on one grant: dropped, the route is JMAP calendars
- [ ] CalDAV resource: the canonical one its RFC 9728 metadata names, never the SRV shard (io-pim-discovery or `SetupPlan.resourceOf`); failing test first; re-verify on device that the Fastmail calendar grant no longer fails with `invalid_target`
- [ ] Waits on jmap-calendar-writes landing (device test, fold)
- [ ] Live check (read-only) that Fastmail's JMAP session serves `urn:ietf:params:jmap:calendars`; record in docs/providers.md
- [ ] setup/SetupPlan.java: for one issuer whose JMAP session serves every chosen domain, rank JMAP over CalDAV and CardDAV so the account is one grant; SetupPlanTest (Google and Microsoft unchanged)
- [ ] e2e: a Fastmail OAuth setup asks one consent (device check owed)
- [ ] delta.md (spec/onboarding.md), fold, log, CHANGELOG
