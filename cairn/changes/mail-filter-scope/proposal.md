---
cairn: change
id: mail-filter-scope
status: active
created: 2026-10-12
---

# The mail filter is what syncs, the chips are views, and nothing syncs on open

## Why

Three things disagree today.

- Opening Trash or Junk alone syncs it (*Junk and trash list when opened*), although only a pull, the drawer's sync and the background run should, and *A role gives direct access across accounts* says no chip changes what syncs. On Gmail each opening lists the label and reads up to about 50 messages whole, for a mailbox whose bodies never download otherwise.
- The filter starts with everything ticked, Trash and Junk included, and says a ticked mailbox is synced (*A sync skips what the filter hides*). A Gmail step never lists Trash or Spam whatever the filter says, so a tick promises a sync that does not happen, and an IMAP account's pull does sync its trash, which the default list then mixes in with the rest.
- On Gmail the step lists the whole account (Gmail's All Mail: everything but spam and trash) and files each message under its labels only. A message filed under no mailbox label (archived) is stored nowhere, so the store cannot say it holds it.

## What

### The filter is what the list shows and what syncs

- A ticked mailbox is listed and synced by a pull, the drawer's sync and the background run; an unticked one is neither. This stays as the spec says, and becomes true on Gmail.
- Defaults, for a store with no kept filter: Trash and Junk unticked on every account, every other mailbox ticked.

### Gmail keeps All Mail under the hood

- A Gmail account holds a hidden collection standing for its account-wide step (role `all`), every message the step brings landing in it beside its labels, an archived one in it alone. It is never shown: not on the filter page, not as a chip, not in a row. It exists so the store holds every message the step brought, and the step never reads one twice.
- The filter page lists the same mailboxes as today.
- Gmail's All Mail holds Sent but not Trash nor Spam: a Sent message comes with the account's step at no extra cost, a Trash or Spam one only by a step over its label.

### The chips are views, and a pull under a chip syncs that chip

- A chip narrows the list for as long as it is on and never syncs by itself: opening Trash shows what the phone holds in it, nothing more.
- A pull while a chip is on syncs only that role's mailboxes of the shown accounts, ticked or not: on Gmail a step over the label (`TRASH`, `SPAM`, `SENT`...), elsewhere a pass over the mailbox.
- *Load more* shows under Trash and Junk too when the list shows them alone, stepping that mailbox, so their older mail is brought only when asked for.

## Out of scope

- Junk and Trash bodies still do not download in the background; *Load more* over them brings what a step brings.

## Open

- Whether the account-wide Gmail step re-reads an archived message at every step today, it being held in no mailbox the `held` check looks at. To verify before the hidden collection; if so, it is a quota leak this change fixes.
