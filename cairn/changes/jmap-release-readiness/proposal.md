---
cairn: change
id: jmap-release-readiness
status: active
created: 2026-10-09
---

# JMAP ready for the release: offered by what the session serves, sending, paged reads

## Why

The standard setup ranks JMAP first for mail, contacts and calendars without looking at what the session offers (`PimDomain.servedBy("jmap")` hard-codes all three, nothing reads a capability URN), while the app cannot send over JMAP and cannot write a JMAP calendar. A Fastmail or Stalwart user keeping the defaults gets a mail account with no compose button and a calendar whose edits fail on every pass. A server without one capability fails the whole JMAP sign-in on the password path, and on the OAuth path saves a domain that never syncs. Reads also have two silent gaps: a calendar listing capped by the server reads as deletions, and an address book past `maxObjectsInGet` never syncs. The read-only gap analysis of 2026-10-09 lists these as its "Build now" and "Gate now" items; this change is those.

## What

- **Capability-gated offer, per-domain probe.** After sign-in, on the password and the OAuth paths, the app reads the session's capability URNs and primary accounts (`urn:ietf:params:jmap:mail` with `urn:ietf:params:jmap:submission` for mail, `:contacts`, `:calendars`). A domain the session does not serve is dropped alone, said so on the screen; the others connect. `PimDomain.servedBy("jmap")` derives its domains from the capability table rather than listing three.
- **Ranking by what the app can do per domain.** JMAP first for mail (sending lands here) and contacts; JMAP calendars below CalDAV until calendar writes exist.
- **JMAP send and sent copy.** The identity matching `From` (`Identity/get`), the message with its `Bcc` taken out uploaded as a blob, `Email/import` into the Drafts role with `$draft` and `$seen`, then `EmailSubmission/set` with the envelope the app builds for SMTP and `onSuccessUpdateEmail` moving it to the Sent role and dropping `$draft` (destroying it when there is no Sent). RFC 8621 refusals are permanent, the rest transient. The compose gate becomes the account trait `submitsOverSession` (JMAP, Graph, Gmail), so JMAP accounts saved without a submit endpoint send after the upgrade with no setup; the sent copy is staged on JMAP as on Graph and Gmail.
- **Paged reads.** `CalendarEvent/query` paged by `maxObjectsInGet`, a listing that stops short never complete; `ContactCard/query` and the delta's `ContactCard/get` paged and chunked the same way.
- **JMAP calendars read only.** A `writesEvents` trait, false for JMAP; JMAP calendars list as not writable, the agenda's add button, the entry page's save and delete refuse with a clear line, and an edit staged by an earlier build is refused for good (422) and shown so rather than failing the pass for ever.
- **Hardening.** `Email/set` `notFound` is converged, the session resource is cached per account and credential (refetched when a mail session opens, after a failure, and past fifteen minutes), and set refusals read as sentences rather than `Debug` dumps.
- **io-jmap**: typed core capability limits, upload and download URL templates resolved by the session, `Display` for the per-object errors, the RFC 8620 and 8621 `tooLarge`, `forbidden`, `overQuota`, `rateLimit` submission refusals, and a `null` `created` or `notCreated` read as empty (Fastmail answers `Email/import` so).

## Out of scope

- JMAP calendar writes (`CalendarEvent/set`), and the single-occurrence traits that come with them.
- `CalendarEvent/changes`, one account-wide `Email/changes` per pass, restore through `Email/import`, drafts, push.
- `ifInState` guards; `maxConcurrentRequests` caps.
