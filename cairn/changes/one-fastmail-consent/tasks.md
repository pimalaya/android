---
cairn: tasks
change: one-fastmail-consent
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

Parked 2026-11-19 by the owner until JMAP calendars work; the same day the owner set up a Fastmail account on JMAP for every domain and met one consent.

- [-] Live check of several `resource` parameters on one grant: dropped, the route is JMAP calendars
- [ ] CalDAV resource: the canonical one its RFC 9728 metadata names, never the SRV shard (io-pim-discovery or `SetupPlan.resourceOf`); failing test first; re-verify on device that the Fastmail calendar grant no longer fails with `invalid_target`
- [ ] Waits on jmap-calendar-writes landing (device test, fold)
- [x] Fastmail's JMAP session serves `urn:ietf:params:jmap:calendars` (device log, 2026-11-19), recorded in docs/providers.md
- [x] The setup already offers JMAP for every Fastmail domain (owner's setup, 2026-11-19): no ranking change needed
- [x] A Fastmail OAuth setup asks one consent: checked on the device by the owner, 2026-11-19
- [ ] delta.md (spec/onboarding.md), fold, log, CHANGELOG
