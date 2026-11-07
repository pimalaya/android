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

The last milestone before the stores. What waits on others (the privacy policy, Google's verification) starts first and runs beside the rest, since its weeks pass outside the code. Then, in order: the data-loss and correctness bugs, the owner's device checks and the folds they unlock, the released libraries and the supply chain, the sign-ins for real users, the security residuals, the gaps a user meets first, then the store listings. An item is done when its own change is landed and archived, or when it is checked here for an item no change holds.

The production track and F-Droid wait for a storage-v1.0.0 store. A store of another draft is refused and the app asks to be reinstalled (offline-store "A store of another draft is not opened"), so a release on storage-draft-09 would send every user to that screen at the next draft, losing the outbox, staged edits and retained items, and signing in again everywhere. The owner decided on 2026-11-07 to release once everything is ready: the store at v1 and the io-* libraries released together at the final validation, kept as git pins until then, builds going to Play internal testing meanwhile.

The 2026-10-09 review items found fixed on 2026-11-06 are left out: time zones and series (calendar-zones-and-series, calendar-occurrence-writes), attachments opened and saved (attachments-as-files), JMAP sending and calendar writes, device-to-device transfer (data_extraction_rules), event reminders through the phone's calendar and new-mail notifications (background-check), a JNI failure answered as an error reply rather than a null (`ErrorReply`, rust/src/ffi.rs).

Out of this milestone: iOS, push (relay), remote search, SecureStore blobs bound to their purpose (audit-s1-residuals I3, later by the owner's choice of 2026-11-07).
