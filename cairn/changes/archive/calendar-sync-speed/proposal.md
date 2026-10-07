---
cairn: change
id: calendar-sync-speed
status: landed
created: 2026-10-07
---

# Calendars read in a few requests, side by side

## Why

The owner's device, a Microsoft 365 test account, first visit to the Calendar tab: three Graph calendars, 23 events in about 10 s, 5 in 0.9 s, 90 in 11 s, so about 22 s for 118 events (about 5 a second) while mail lists hundreds a second. Calendar passes logged no timing.

Reading the path settles where the time goes. A calendar pass lists the calendar (one request) and then names every entry it does not hold at its revision by reading it: on Graph, `read_graph_events` sent one `GET /me/events/{id}` an event and, for a series, one more `/instances` listing per window of up to five years (three for an open series such as a birthday). That is about 190 requests for 118 events, each a round trip, one calendar after another on one transport. JSON, the engine and the store are the mail pass's figures, a few milliseconds a page. Google had the same shape (a `GET` an event and an `iCalUID` listing a series) although its listing had already read every event whole; JMAP re-listed the whole calendar for each read of 64. No calendar is projected into the phone's calendar provider, so there are no provider writes to batch.

## What

1. **Graph reads by `$batch`.** An entry read sends its events 20 to a `$batch`, then the first page of every series window 20 to a `$batch`, next links followed alone; a reply the batch could not serve (throttled inside it) is sent again on its own, where the transport waits out the throttling. The requests are the ones the single reads sent, same `$select` and `$expand`. An event gone between the listing and the read (404) is left out, as a `calendar-multiget` leaves out a missing resource, where it used to fail the pass.
2. **Google and JMAP bodies from the listing.** Their complete rounds already read every event whole, so the round hands the bodies over with the names (`EventDelta.bodies`), and the engine names an entry from the listing rather than reading it again; an entry bound at its revision is left as it was.
3. **Calendars side by side.** An account's calendars run on a pool of three transports (`CalendarPool`), the listing's transport the first worker's, in the order the account lists them; the store keeps one writer (`PimdirEngine.STORE`); a calendar failing leaves the others running; the token session refreshes once for all workers.
4. **Timing.** Calendar pages log the mail pass's per-page line (network, JSON, engine, store), and each account's run one line with its wall time against the network summed.

## Out of scope

- Graph's event delta (`maxpagesize` on delta pages): the calendar path does not use it, every round being a complete `/events` listing at 500 a page (calendar spec); unchanged.
- A Graph first pass listing bodies with the names: it would carry every event's body on every pass, where batched reads cost one request per 20 changed events.
- A default calendar first: the account's listing order is kept (three workers start three calendars at once).
