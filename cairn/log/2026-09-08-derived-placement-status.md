---
cairn: log
change: derived-placement-status
landed: 2026-09-08
---

# The store derives what a placement owes, and a calendar pass puts its bodies back

Two faults the first run of the offline-first writes turned up, one cosmetic and one that emptied the agenda.

**A calendar pass dropped every body and put none back.** io-pimdir's sync does not fetch: when it finds the remote content changed it releases the object and leaves the placement below full, on purpose, so the exchange is not tied to the download. An upgrade pass is what refetches, and the contacts driver has always run one; the calendar driver did not. An agenda reads its entries by their body, so a pass left the calendar empty and nothing ever put it back. `hydrate` moved onto `PimdirEngine` and the calendar pass runs it. Mail deliberately does not: a mailbox is a spine and a message rises off it by being opened, so a placement below full is the ordinary state of one.

**The store never told the engine that anything was pending.** pimdir SYNC §3 has the projection derive a placement's status from the row, by five rules; the store implemented two of them and answered `clean` for everything else. So `dirty` was never projected, and a staged edit came back to the merge as agreed: the write landed, the sync reported success, and the change was never sent. And `created` was never projected either, so an item no source binds came back under a provisional handle that no listing matches, which a complete round reads as a member the remote lost and retires. That is what took the edited event: not the edit, the projection.

The projection now follows the standard: conflict, tombstone, created, dirty, clean, first rule that applies; an item no source binds and no body is held for is projected for nobody, rather than offered under a handle nothing can match; and a placement holding no body projects at most meta whatever the stored level claims, which is what has the upgrade refetch it. Two details the rules are precise about and the obvious reading gets wrong: the body axis is for mutable kinds only, so the bytes a reader stored by opening a message are not an edit owing an upload, and flags are compared as the sets they are rather than as the text they were written in.

**A calendar's listing moved to the caller.** It was read inside the enumerate yield, so a merge that enumerated twice would have made two requests; the pass reads it once and the enumerate and the fetch are both served from it, which is the shape the mail driver already had. A push the server refuses on its precondition is now reported as a rejection rather than raised, so one stale ETag no longer aborts the round and takes every change after it down.

**The sync dialog says the same things in every domain.** It carried a title and a detail line, and only contacts filled either in; mail and calendar sat under "Preparing synchronization" for the whole pass. Both now name the mailbox or the calendar they are on and report the step beside it, off the progress seam that also moved onto `PimdirEngine`.

**One thing that heals itself rather than being migrated.** A store written by the read-only mirrors holds bindings whose base names a revision and no body, which the rules read as dirty. The first pass after this pushes each of those once, the server accepts, and the accepted push rebases the placement with the body it sent; every pass after it is quiet. A migration would have had to move refcounts to save one redundant round trip per item, once.

Capabilities moved: offline-store, calendar.
