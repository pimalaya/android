---
cairn: log
change: a-send-refused-for-a-rate-stays-queued
date: 2026-11-06
---

# A send refused for a rate or a quota stays queued

A fix to a production-release section 1 check, folded without a proposal.

Capabilities moved: mail (modified: a submission refused for good is parked, a refusal for a rate or a quota no longer counting as one).

Graph and Gmail parked a submission refused with 400, 403, 404, 413 or 422 as refused for good (`refused_by_api`). Gmail answers 403 for its rate limits and its daily quotas too: the throttle retries a rate-limited one within its bounds, then hands the 403 back, and a daily quota is handed back at once, so a send the quota held parked as refused, waiting for the user's *Send again*. The throttle now records on the exchange whether the answer refused the request for a rate or a quota (`Client::limited`, a 403 or a 429 it reads as throttled, or naming a daily quota), and `Refused::http` never makes such a refusal permanent: the row stays pending. Tests: `a_rate_or_quota_refusal_handed_back_is_limited` (rust/src/client/throttle.rs), `a_send_refused_for_a_rate_or_a_quota_stays_queued` (rust/src/mail/session.rs).

Left from the same check: a Google Calendar insert rerun under the same iCalUID, checked live first (production-release section 1).
