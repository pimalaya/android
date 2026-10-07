---
cairn: log
change: calendar-sync-speed
landed: 2026-10-07
---

# Calendars read in a few requests, side by side

Capabilities moved: calendar (added: An account's calendars sync side by side, Each calendar page's time is logged; modified: A Microsoft calendar runs over Graph, A Google calendar can run over the Calendar API, and the introduction).

The owner's device took about 22 s for the first sync of three Graph calendars (118 events). The cost was requests: after its one listing a calendar, a pass read every entry it did not hold by one `GET` an event plus one `/instances` listing per window of each series (three for an open series), about 190 round trips one calendar after another; nothing is projected into the phone's calendar provider, so there was no provider write to batch.

**Graph.** `read_entries` (over the `GraphReads` seam, the client or a fake) sends the events 20 to a `$batch` and then the first page of every series window 20 to a `$batch`, next links followed alone, with the `$select` and `$expand` the single reads sent; a reply the batch could not serve is sent again alone, and a 404 leaves the event out where it used to fail the pass.

**Google and JMAP.** Their complete rounds hand the bodies over with the names (`EventDelta.bodies`); `PimdirEngine.named` names a member from what the listing carried and drops it for a member bound at its revision. Google built each entry from its listing already for its revision, and now builds its body there too, where it read each event and each series' instances again.

**Side by side.** `CalendarPool` runs an account's calendars on three transports, the listing's the first worker's, in the account's order; the store keeps one writer (`PimdirEngine.STORE`, `bound` now under it); a calendar's failure leaves the others; `SyncRunner.Session` renews a refused token once for every worker. Calendar pages log the per-page clock line and each run `calendar pass <account>: N calendars on K sessions in W ms, remote summed R ms`.

`gcal`: a listing names each entry with its body at its folded revision. **Tests.** `graph_calendar_tests`: the batched read builds the entries the one-by-one read built, a throttled batch read again alone builds them too, 120 events over three calendars in 9 requests against 165, a gone event and a series gone before its instances left out, a window of several pages followed, the batch URLs equal to the single reads' request lines. `CalendarPoolTest` (20 ms a request, the real calendar driver, engine and store): 120 events in about 105 ms side by side and batched against about 2.57 s one by one in a row, the same entries stored, no transport shared, no request under the store lock, writes serialized; listed bodies reading nothing more; a changed entry read alone; one failing calendar leaving the others.
