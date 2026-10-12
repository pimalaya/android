---
cairn: log
change: mail-filter-apply
landed: 2026-10-12
---

# The filter decides what is on the phone, applied with a button

Capabilities moved: offline-store (modified: a mail account other than Gmail ticks its inbox alone to begin with, a Gmail one every label but Trash and Spam; added: the filter page's ticks are a draft until *Apply*, which syncs what they newly take), mail (modified: the first step takes the ticked mailboxes, *Load more* steps mailbox by mailbox off Gmail, a mailbox's own frontier rules off Gmail, a JMAP account step keeps to the ticked mailboxes).

`MailStore.replaceMailboxes` marks an account listed for the first time off Gmail (`mail-inbox-only`) and hides every mailbox of it but the inbox, once each (`MergedFilter.quiet`); an account listed before keeps its ticks. `RemotePass.stepMail` gives the account's step the ticked mailboxes alone, and `GmailStep` names them in the request, which the bridge's `jmap_step` turns into `inMailboxOtherThan` every other mailbox. `MailList.footerOf` answers `MailList.Step`s: one per Gmail account (or its one label shown alone), one per shown mailbox with more elsewhere, the junk or the trash alone as before; `MainActivity.loadMore` takes them account by account on one session, and the footer's line says *per mailbox* when any steps one. `MailWindow.since` and `more` read a mailbox's own frontier alone off Gmail (`ImapStep` downloading from it).

`FilterPage` edits `MergedFilter.draft()` and `apply()`s it on *Apply* (`filter_apply`, `filter_actions` taking the bottom inset), closing dropping it; `isActive` reads hidden only what the default shows. `MainActivity.applyFilter` finds what the draft newly shows: mailboxes never listed get `RemotePass.firstOf` (`MailEngine.widen` by one chunk, the mailbox's own frontier set to its floor, bodies planned after), books or calendars their domain's pull. A mailbox unticked is left out of the body plan (`planBodies`, what it owes kept) and the background fill.

Also: onboarding scrolls a domain just ticked, or a card whose configuration was just picked, to the top of its page (`OnboardingFlow.reveal`); finishing onboarding over a list other than the one the account lands on swaps the list in under the closing sheet (`goDomain`), where the agenda used to stay under the mail list's chrome and sync.

Tests: `MailFilterDefaultsTest` (defaults per provider, an account listed before, the draft), `MailWindowTest.offGmailAMailboxReadsItsOwnFrontier`; `MailFilterTest` and `MailStepTest` moved to the inbox-only default and the per-mailbox line.
