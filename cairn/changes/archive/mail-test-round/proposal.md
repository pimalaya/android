---
cairn: change
id: mail-test-round
status: landed
created: 2026-10-06
---

# What a round of mail testing against Fastmail turned up

## Why

A test pass of mail against a Fastmail account reported five problems besides the dates (still open, see the log):

- **A mail account connected with SMTP could not send.** The connection flow built the account with its submit endpoint, then persisted it through `SecureStore.connect`, which took a base URL and a credential and nothing else, so the endpoint was dropped on the way to disk and the composer reported SMTP as not configured.
- **Flags did not move after a sync.** The drawer's sync ran the contacts pass alone. A marker staged on the phone waited for a pull on the mail list, which is the one thing that synced mail, and a marker moved on the server was never read either. The engine itself was checked against a scripted remote, full and QRESYNC-shaped rounds, both directions: it carries every change.
- **The sync dialog's title named collections and accounts.** It read as a list of mailbox names and addresses flicking by.
- **The filter offered accounts that could hide nothing.** The account axis listed every account whatever the domain on screen, `local://on-this-device` included, which holds one address book and no mail or calendar.
- **A deleted account's mail and events stayed listed.** Deleting an account dropped its address books and left its mailboxes and calendars in the store, under an account the filter no longer offered.

And one request: a sync should not reconcile the mailboxes the filter hides.

## What

- `SecureStore.connect` takes the submit endpoint, and the flow passes it.
- The drawer's sync runs contacts, then mail, then calendars. Each domain's pass is a method the per-domain refreshes share.
- The dialog's title is the domain: *Emails*, *Contacts*, *Calendars*. The detail line is unchanged. `SyncRunner.Observer.bookStarted`, which only titled the dialog, is gone.
- A mail pass skips a mailbox the filter hides, by account or by mailbox. The roster is still listed, so the filter keeps offering it.
- The account axis lists the accounts covering the domain on screen, never the on-device one.
- Deleting an account drops its mail collections, its outbox and its calendars.
