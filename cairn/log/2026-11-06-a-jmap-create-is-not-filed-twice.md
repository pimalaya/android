---
cairn: log
change: a-jmap-create-is-not-filed-twice
date: 2026-11-06
---

# A JMAP create is not filed twice

Part of the duplicate creates left after network-trust-hardening M6 (production-release section 1), folded without a proposal.

Capabilities moved: offline-store (added: A JMAP create is not filed twice).

A create whose answer was lost stays staged and runs again on the next pass, so a JMAP server holding the first one got a second event or card. JMAP has no create key that outlives a request (a creation id holds within one, RFC 8620 section 5.3), so `JmapCalls::create_event` and `create_card` first query the calendar or the address book for the object's `uid` (`CalendarEvent/query`, `ContactCard/query`, one request more per create) and answer the one found. The card create moved from `Jmap` to `JmapCalls` so a test drives it over the fake session. Tests: `a_new_entry_the_calendar_holds_already_is_not_filed_twice` (rust/src/calendar/jmap/tests.rs), `a_new_card_the_book_holds_already_is_not_filed_twice` (rust/src/contact/jmap.rs). Not run live: Fastmail is pull-only for tests and its token offers no contacts or calendars.
