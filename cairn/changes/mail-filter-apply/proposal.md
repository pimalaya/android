---
cairn: change
id: mail-filter-apply
status: active
created: 2026-10-12
---

# The filter decides what is on the phone, applied with a button

Follows `mail-filter-scope` (2026-10-12).

## Why

The filter now says what shows and what syncs, but three things still disagree with it.

- An account's first sync takes every mailbox, not what the filter ticks. On Microsoft the first step lists the ~50 newest across every mailbox but the junk and the trash, then each mailbox's headers back to the oldest of them, whose bodies download behind it. The phone ends up holding Sent, Archive and the rest though no one asked for them, at a cost in quota.
- A tick applies at once and silently: ticking Sent shows a Sent the phone never listed, empty or partial, and nothing brings it until the next pull.
- *Load more* brings about 50 per account, so one busy mailbox takes the whole step and the others lag behind.

## What

### What is ticked to begin with

- Gmail: every label but Trash and Spam, as today. Gmail is the one account synced through All Mail in one listing, so its labels cost nothing more.
- Every other account (IMAP, Microsoft, JMAP): the inbox alone. Every other mailbox is unticked the first time a roster lists it (extending `MergedFilter.quiet`), and ticking it is the user's choice.
- Accounts connected before this change keep their ticks.

### The filter page applies with a button

- Ticks become a draft: the page carries an *Apply* button, and leaving it by back or the scrim discards the draft. The list behind changes only once applied.
- Applying a tick on a mailbox never listed starts its first sync at once, under the sync dock: about 50 of its newest messages, their bodies downloaded, as an account's first step does for its inbox. A mailbox ticked again after having been listed is caught up by the next pull, nothing more.
- Applying an untick hides the mailbox's mail from the list and stops every further sync of it: pulls, the drawer, background runs, the body download and *Load more*. Nothing is deleted; ticking it again shows what was kept.
- Contacts and calendars take the same button: a book or a calendar ticked is synced at once by a pass over it, one unticked is hidden and no longer synced.

### The first sync takes what is ticked

- An account's first sync steps its ticked mailboxes only: the inbox alone on IMAP, Microsoft and JMAP, every label but Trash and Spam on Gmail.

### *Load more* is per mailbox

- Off Gmail, *Load more* takes one step of each ticked mailbox of each shown account, about 50 each, one after another: the footer reads *About 50 more per mailbox*.
- Gmail keeps one account-wide step, about 50 across its labels, since one listing of All Mail serves them all.

## Out of scope

- Freeing the stored mail of an unticked mailbox.
- The account's months bound (Settings), which still bounds every mailbox.

## Open

- The body download (`MailBodies`) plans over every mailbox today; it is narrowed to the ticked ones here, which the untick rule requires.
