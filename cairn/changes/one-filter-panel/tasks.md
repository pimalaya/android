---
cairn: tasks
change: one-filter-panel
---

# Tasks

- [x] Design boards on the canvas (Filters 1 to 4)
- [x] Review the proposal (the user, 2026-10-30: inline accordion, pills when folded, sync in account settings; Tasks first)
- [x] Step 1, Tasks header: count line, search over summary and categories, review line out, Weekly review button accented when due; strings en and fr
- [ ] Step 1 device check (the user)
- [x] Step 2, first cut: what syncs is chosen from the account's settings, a row per domain (Mailboxes, Calendars, Address books that sync, counted for mail and calendars) opening the existing filter page scoped to that account (`FilterPage.openSync`), its Apply first sync and *Download* kept; the filter page moved above the account overlay; the calendar rows say Synced or Not synced. `MergedFilter` stays the sync scope, so no sync call site moved and no migration was needed.
- [ ] Step 2, later: the per-collection switches of board Filters 4 in place of the scoped page
- [ ] Step 2 tests: a collection hidden from a list still syncs (no test yet; nothing in the sync path changed)
- [ ] Step 2 device check (the user)
- [ ] Step 3, inline panel: filter button in the search field, the panel per list as section rows opening checklists, draft with *Reset*, *Cancel* and *Apply*, the folded pills, chip rows and the filter overlay out; Tasks, then Mail, Calendar, Contacts, Files
- [x] Step 3 on Tasks: `FilterPanel` (ui), `ListHeader` filter button and panel slot, `TasksList` Categories / Status / Due / Calendars kept in prefs, open then done then cancelled by default, square row boxes, bar filter hidden on Tasks; Calendars hides from Tasks alone until step 2
- [x] Step 3 on the other lists: `ListFilter` (ui, a set per section, applied and drafted, kept per list) and `FilterPanel.choice` / `checks` / `collections`; Contacts (Address books, Show All or Default), Files (Folders, Show All / Folders / Attachments, Type), Calendar (Calendars, Show All or Default, and a title search so the header matches), Mail (Mailboxes, Show role, Only Unread / With attachments; Apply reloads, the store answering the query); every chip row and the bar's filter button gone
- [x] Round 2026-10-30 evening: the filter button stays while the panel is open, folding it as Cancel does; Mail's sections read Role and Flags (Unread, Read, With attachments); Calendar adds Kind (Events, Tasks, Journal entries), When (Upcoming, Past), Time (All day, At a time) and Has (A location), and lists what is over under the rest as "Past · <day>", the last first, dimmed; Contacts adds Has (email, phone, address, birthday, organisation, photo, any on), Kind (`KIND`, RFC 6350 6.1.4) and Categories (every card's, `CardFacts`, `CardFactsTest`)
- [ ] Not done: Mail flags Starred and Answered (the canonical mail statements bind `seen` and `attachment` only; a `flagged` parameter is io-pimdir's to add); event status, categories and attendees as agenda criteria (the occurrence the bridge hands carries none)
- [ ] Not done: Mail's Received date (needs a date bound on the store's mail query, an io-pimdir statement); the Files tab's old filter (`MergedFilter.files`) is no longer read, its choices dropped, and `FilterPage.openFiles` is unreachable
- [x] Weekly review to its design: large title, last-done line, one card of numbered steps, Finish a real button held at the bottom; "Next actions" step renamed "Active tasks"
- [ ] Step 3 tests: the due comparison (`TaskOrderTest`); pills and live refilter are checked on the device
- [ ] Step 3 device check per list (the user)
- [ ] Spec delta folded (offline-store.md, mail.md, tasks.md), the sync rules of `mail-filter-apply` and `mail-filter-scope` moved under account settings, log, CHANGELOG
- [ ] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`
