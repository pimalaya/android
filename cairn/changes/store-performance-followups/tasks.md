---
cairn: tasks
change: store-performance-followups
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, Rust to rust/src/. Numbers are the P1/P2 audit's.

- [ ] 10. calendar/CalendarList.java:246: events prefiltered by the event summary before their bodies are read (io-pimdir statement, asked upstream if missing); a test with 10k events where only the window's are parsed
- [ ] 11. mail/MailStore.java:890: `mailboxes()` (line 1051) read once per refresh, not per page
- [ ] 14. MailStore.java:1416 (`collectBefore`), 1453 (`release`), 1515 (`releaseCrossLinked`): refcounts adjusted for the touched objects only; StorePerformanceTest asserts the counts equal a full recompute
- [ ] 15. contact/PimdirContacts.java:95-117: list rows from the summary, the card parsed on open
- [ ] 16. mail/MailOwed.java:55, 69: owed keys in a table (or bounded), one write per change
- [ ] pimdir/PimdirBlobs.java:132: no `(int)` cast past 2 GiB; PimdirQueue.java:187, PimdirDb.java:389, PimdirItems.java:80 read through `sourceFile` or a stream where they can
- [ ] ffi/mail.rs:341 and 396: `parseMessageFile` and `messagePart` over a memory map, the part streamed; a 100 MiB attachment opened under 100 MiB native
- [ ] Build: assembleDebug, app and client unit tests, cargo fmt/clippy/test, cairn/verify.sh
- [ ] Log written; CHANGELOG for what a user feels (agenda, contacts list)
