---
cairn: log
change: platform-sqlite
date: 2026-11-01
---

# The store and the index on the platform's SQLite: landed

Steps 1 to 5, each checked on an Android 15 phone and the last two on an API 26 emulator (SQLite 3.18.2), landed together with cairn/changes/archive/the-shape-follows-storage-draft-08, which ran step 2 at storage-draft-08 as its steps 2a and 2b, and cairn/changes/archive/lists-from-the-index, built as step 5. What each step built is in cairn/log/2026-11-01-platform-sqlite-step-3.md, -step-4.md and -step-5.md, and step 2 in cairn/log/2026-11-01-the-shape-follows-storage-draft-08.md. The change is archived at cairn/changes/archive/platform-sqlite.

Capabilities moved:

- **offline-store** (spec/offline-store.md): ADDED "The store's SQL holds to the oldest platform SQLite", "The index shares the store's SQLite", "The index is the app's own shape over FTS4", "The store runs on the platform SQLite", "The store's writer syncs every commit", "An incompatible store blocks start-up"; REMOVED "The store runs on the bundled SQLite". The introduction no longer says the app bundles SQLite.
- **mail** (spec/mail.md): ADDED "The mail search reads the index"; MODIFIED "The mail list narrows what it shows" (a search over every field, the body included, through the index).

The delta was amended at landing: its "The trash opened" scenario had taken "Widening again"'s outcome, which contradicts the requirement it illustrates; it keeps the spec's.

## Left

- The store's readers keep the platform's `synchronous` default under write-ahead logging, where STORAGE §4.1 asks FULL per connection; they commit nothing, so nothing a power loss could undo rests on them.
- `SQLITE_DBCONFIG_DEFENSIVE` (STORAGE §18, a SHOULD) is a C call the platform's Java reaches at no API level: not set.
- index.db is written under the process's own lock (`Locks.index`), not the exclusive `flock(2)` on index.lock SEARCH §3 asks of an indexer; the app is the only process writing it.
- The search's coverage (`horizon_left`, `cut`, the temporal answer, the bodiless counts) is computed nowhere for the mail search, and the vectors' coverage is not compared; the agenda says its own horizon and cut (lists-from-the-index).
- The JVM tests run Robolectric's SDK 36 classes over SQLite 3.18.2, not API 26's classes (one sandbox per JVM holds the host library).
- Not measured on a device: index.db's size after a large first sync (the host's figure, 9.0 KB per synthetic 2.1 KB message, is an upper side).
