---
cairn: log
change: sync-strip
landed: 2026-10-09
---

# The sync dialog becomes a strip under the lists' titles

From the `quiet-first-sync` design frame (`NewArrive`), taken on its own: a running sync no longer sits behind a non-cancelable dialog. Every list's large title carries, while a pass runs, the account (*me@example.org*) over the step (*Mailboxes synced: 3 of 12*) over a 4dp bar, determinate while an account's mailboxes land and indeterminate otherwise; the meta line comes back when the pass ends.

The dialog was also what kept the app still during a pass, so its guards move into the code: one pass at a time (every sync entry returns while one runs, a pull included), no account deletion during a pass (a toast says to wait), back works again, and an account connected during a pass gets its owed first sync once the pass ends.

Capabilities moved: offline-store (the progress requirement rewritten, with a scenario for reading while a pass runs), onboarding and mail (wording: the first sync runs under the strip rather than behind a dialog). The rest of `quiet-first-sync` (inbox first, filter-driven sync) stays a draft.
