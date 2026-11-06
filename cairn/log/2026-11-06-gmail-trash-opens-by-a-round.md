---
cairn: log
change: gmail-trash-opens-by-a-round
date: 2026-11-06
---

# Gmail's trash and junk open by a round of their own

A fix found reading the code for the production release (production-release section 1), folded without a proposal.

Capabilities moved: mail (modified: the Gmail step, its first step no longer opening the junk and the trash).

A Gmail step lists the account with `includeSpamTrash=false`, so it brings nothing to the junk or the trash. Its first step still opened them, empty, as rounds closed on the step's `historyId`, so when the setup had them shown, later passes replayed history from there and the mail they already held never listed, short of *Load more* on that chip alone. `GmailStep` now opens at the first step only the mailboxes whose role downloads bodies (`MailBodies.downloads`); the junk and the trash keep no coverage, and their first pass is a round over the label. Test: MailStepTest `aStepLandsWholeMessagesInEveryLabelAndMovesTheFrontier`, restated.

Also restated, not a code change: B.13 rule 7 (the junk and the trash listed on opening) was removed on 2026-10-12 (mail-filter-scope), so its open tasks in mail-load-more and e2e-suite are closed.
