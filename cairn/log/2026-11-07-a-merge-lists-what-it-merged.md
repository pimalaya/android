---
cairn: log
change: a-merge-lists-what-it-merged
date: 2026-11-07
---

# A merge lists what it merged

A bug found on 2026-11-07 while testing google-tasks-lost-create, folded without a proposal.

Capabilities moved: none; the code now meets conflicts.md's triage, whose merge is the item as it stands.

A calendar item edited on both sides and merged by the sync's triage (`CalendarPhonePass.triage`, run by `CalendarEngine.triage` and by the phone pass's settle) was staged with no summary, which keeps the stored row: the body held the merge while the Tasks tab and the agenda, which read the summaries and the index, kept the old title. The triage now derives the summary and sort key from the merged body (`CalendarSummaries.of`), as the stager does. Tests: TaskSyncTest `aToDoMergedByTheSyncListsWhatItMerged`, CalendarDavWriteTest `anEntryMergedByTheSyncShowsWhatItMergedInTheAgenda`, both failing before.
