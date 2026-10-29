---
cairn: log
change: gtd-hardening
date: 2026-10-29
---

# Tasks the standard's way, filed by a move, refused one at a time

Android's part of unfog's cairn/changes/gtd-hardening, on io-pimdir 8907303 and io-webdav b2b9a00.

- **tasks** (spec/tasks.md): a to-do edit follows pimdir GUIDE §17 and passes its writer vectors (rust/src/calendar/vectors.rs), an unchanged edit staging nothing; the Tasks tab reads its to-dos from `task_summary`, deriving once the summaries an older store lacks; each list has one order (Next by due, priority, then oldest captured, the others oldest first) and the category filter folds; a capture goes only to a calendar taking tasks (`x-vtodo`, declared at listing, the source registered with no checkpoint so it holds before the first sync); filing a task elsewhere is one transaction staging an add superseding the source, delivered by a WebDAV `MOVE` then a write of its body where the server can, so Fastmail, keeping one `UID` per account, takes it; the source's delete held until the add is pushed; a reply marks a Waiting For through the canonical `list_replied`, the person's own Sent and Drafts aside.
- **calendar** (spec/calendar.md): a 403 or 409 on one create refuses that entry alone, a 401 or a throttle still failing the pass; a delete of an entry already gone is done, a 412 read back when the server holds nothing there.
- **offline-store** (spec/offline-store.md): every listed or staged calendar body carries the standard's summary; a calendar or address book outside the home is addressed by its path.

Capabilities moved: tasks, calendar, offline-store.

Verified on 2026-10-29: `cargo test` 532 green, `gradle :app:testDebugUnitTest` 780 green, `:app:assembleDebug`, fmt and clippy clean, cairn/verify.sh conformant; the regression tests named in tasks.md failing on the earlier code; E2eTaskTest live on `fastmail-caldav`, scratch calendars only, the filed task ending in the target alone, both passes clean, nothing left on the home.
