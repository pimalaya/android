---
cairn: tasks
change: new-task-page-and-drop-in
---

# Tasks

- [x] `TaskPage.openNew`: empty page, calendar picker, `TaskWrites.create` on save, title asked first
- [x] Add button labelled New task (`task_new`), `task_add` dropped
- [x] `MainActivity.show` / `showBack` drop in and lift away; auth steps unanimated; slide anims and `stay.xml` removed
- [x] Leaving a page or an overlay unanimated, `fade_zoom_out` removed
- [x] Task page order: calendar, Organise (with Waiting on), Details (with Due), Progress; When removed
- [x] Organise always in sight, its Categories row too (a bar button hiding it was tried and dropped, 2026-10-30)
- [x] The calendar a tappable row on the event page too (a picker dialog in place of the dropdown), and an Address book section first on the contact page, a tap picking the book of a new contact or placing a stored one (the bar's books button gone)
- [x] Cross on the contact, editable event, task and composer pages (`closingBack`), the arrow restored by the chrome reset
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest` (796)
- [ ] Device check (the user)
- [ ] Spec delta folded (tasks.md, offline-store.md), log, CHANGELOG
