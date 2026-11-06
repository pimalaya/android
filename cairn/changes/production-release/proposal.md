---
cairn: change
id: production-release
status: active
created: 2026-11-06
---

# The production release (milestone M7)

## Why

The app is built for every domain, but what stands between it and the stores is spread over docs/production-review.md (2026-10-09, partly stale), 60 changes in cairn/changes and the plan's M7 line. One checklist makes the release followable: each item names the change that holds its detail.

## What

The last milestone before the stores, in order: the data-loss and correctness bugs, the owner's device checks and the folds they unlock, the released libraries and the supply chain, the sign-ins for real users, the security residuals, the gaps a user meets first, then the store listings. An item is done when its own change is landed and archived, or when it is checked here for an item no change holds.

The 2026-10-09 review items found fixed on 2026-11-06 are left out: time zones and series (calendar-zones-and-series, calendar-occurrence-writes), attachments opened and saved (attachments-as-files), JMAP sending and calendar writes, device-to-device transfer (data_extraction_rules), event reminders through the phone's calendar and new-mail notifications (background-check).

Out of this milestone: iOS, push (relay), remote search.
