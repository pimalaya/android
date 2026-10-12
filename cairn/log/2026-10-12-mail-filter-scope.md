---
cairn: log
change: mail-filter-scope
landed: 2026-10-12
---

# The mail filter is what syncs, the chips are views, and nothing syncs on open

Capabilities moved: mail (added: a Gmail account keeps its step's messages in a hidden All Mail; modified: nothing syncs on opening a mailbox or turning a chip on, a pull under a chip syncs that chip's mailboxes, the drawer's sync and a background run skip what the filter unticks, *Load more* steps or widens the junk or the trash shown alone; removed: the junk and the trash listed when opened), offline-store (modified: the junk and the trash start unticked, a Reset returns to that, the role chips show their mailboxes ticked or not).

A Gmail step lists the account newest first and reads whole the messages the store does not hold in the step's mailboxes; an archived message, filed under no mailbox label, was held nowhere, so every step read it again, and a run of archived mail could keep *Load more* from ever reaching older mail. `MailStore.ALL_MAIL` (`\u0001all`, like the outbox) names a collection every Gmail step item lands in (`GmailStep`), with no body; `PimdirCollections.list` never returns it (`listAll` does, for the window's collection), `MailEngine.accountCollections` counts it as held, and `RemotePass.stepMail` adds it to a Gmail step, creating it where the roster has not been read since (`ensureAllMail`). A step over the trash or the junk alone asks over that mailbox alone.

`MainActivity.listAlone` and `MailList.alone` are gone. `MailList.footerOf` offers *Load more* under the junk or the trash its account shows alone (`moreAlone`: on Gmail its own frontier, `MailWindow.moreAlone`; elsewhere until it is listed down to the bound), and `RemotePass.loadMore` steps it by label on Gmail and widens it by a chunk elsewhere. `MergedFilter` is one instance per list for the process, and hides the junk and the trash once, the first time `MailStore.replaceMailboxes` lists them (`quiet`); `reset` and `isActive` measure against that default. `SyncScope.mail` scopes the drawer's mail to what the filter ticks, as `BackgroundJob.scope` does for mail. `MailList.narrowed` is now a `SyncScope` over the shown accounts' role mailboxes, ticked or not, and `MainActivity.syncMail(role)` pulls with it.

Also: *Load more* no longer asks the server for the outbox (*No mailbox named Boîte d'envoi*), which `MailStore.loadMailboxes` lists for the filter page.

Tests: `MailStepTest.anArchivedMessageIsHeldInAllMailAndNotReadAgain`, `MergedFilterTest.theJunkAndTheTrashStartHidden` and `theDrawersMailSyncsWhatIsTicked`; `MailFilterTest` moved to the chips' new reach and the trash's default.
