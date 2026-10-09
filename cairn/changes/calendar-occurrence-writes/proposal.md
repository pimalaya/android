---
cairn: change
id: calendar-occurrence-writes
status: active
created: 2026-10-09
---

# One occurrence written to Graph and Google, not only to CalDAV

## Why

`calendar-zones-and-series` writes one occurrence as an override component (`RECURRENCE-ID`) and one deleted occurrence as an `EXDATE`. CalDAV carries both, since the object is the resource. Graph and Google do not: io-msgraph's `update_from_ical` and io-gcal's `from_ical` write the series master alone, so an override staged on those calendars would silently never reach the server. The entry page therefore offers "This occurrence" on save only for CalDAV, and on delete for CalDAV and Google (`writesOverrides`, `writesExdates` in `accountInfo`). The phone calendar mirror makes this worse: a calendar app editing one occurrence on a Graph or Google calendar produces an override the push would drop.

Both APIs model an occurrence as an event of its own: Graph's `GET /me/events/{id}/instances` lists them with an id each, `PATCH`/`DELETE /me/events/{instance-id}` writes one; Google's `events.instances` (with `originalStart`) finds one, `events.patch`/`events.delete` on the instance id writes one. io-msgraph and io-gcal already wrap the listing.

## What

- **Push, per override:** a staged object whose overrides or `EXDATE`s changed against the base is pushed as the series master (as today, without its overrides) plus one write per changed occurrence: the instance found by its original start (Graph `instances` over a window around it, Google `instances` with `originalStart`), then patched with the override's fields (projected as each library already projects a single event) or deleted for a new `EXDATE` / cancelled override. An override removed (the occurrence reverted) is a reset where the API has one, else a patch back to the master's fields.
- **Read side unchanged:** both backends already fetch series with their exceptions (`to_ical_series`, Google's instances fold).
- **Traits:** `writesOverrides` and `writesExdates` become true for Graph and Google once their writes land, so the entry page offers the full choice everywhere; the traits stay for any backend that cannot.
- **Library gaps** go into io-msgraph / io-gcal as path dependencies until released: an instance patch built from one override component if the existing single-event projection does not fit.

## Out of scope

- JMAP calendars (no calendar writes yet, docs/production-review.md).
