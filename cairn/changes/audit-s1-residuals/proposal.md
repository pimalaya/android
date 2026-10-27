---
cairn: change
id: audit-s1-residuals
status: active
created: 2026-10-18
---

# The S1 audit's residual findings

## Why

The S1 app-surface audit of 2026-10-11 (snapshot android-audit) found 2 medium, 6 low and 8 info findings. M1, M2, L2 and L4 to L6 are fixed (crash-and-surface-hardening, write-once-and-oauth-dos), L1's account and authority checks too (The sync adapters serve their own accounts alone). Checked against the code on 2026-10-18, these remain:

- **I1, notifications on a secure lock screen.** BackgroundJob.notify (BackgroundJob.java:96) posts sender and subject with the default visibility and no public version, so what a locked phone shows depends on the user's global setting alone. Its channel is named by the email address (`"mail-" + email`, lines 103 to 105), which system settings then list.
- **I2, recents.** No FLAG_SECURE and no `setRecentsScreenshotEnabled(false)` anywhere: the recents thumbnail shows the open message or contact. Normal for a mail client, cheap as an option.
- **I3, one key, interchangeable blobs.** SecureStore (account/SecureStore.java:51, `seal` line 270, `open` line 282) runs AES-GCM with no associated data, so the accounts blob and a pending grant blob (PendingGrantStore.java:48) open under each other's name.
- **I6, a session closed while used.** client MailSession.java holds a raw bridge pointer (`handle`, line 13) that `close` (line 103) zeroes with no lock. Safety rests on the single io executor and MailPool's lock; a second executor would make close-during-use a use after free.
- **I7, a hostile shared book.** Raw contacts of a book the user does not own aggregate with the user's own contacts (a planted card bearing a relative's address adds a phone number to that contact), and an app holding WRITE_CONTACTS or WRITE_CALENDAR can delete every row, which the phone pass carries upstream (contact/PhoneRemote.java): no too-many-deletions guard exists.
- **L1, autoInitialize.** Both adapters still call `super(context, true)` (SyncService.java:59, CalendarSyncService.java:58): an initialize request runs `setIsSyncable(account, authority, 1)`, though Accounts.reconcile* already marks the accounts syncable.
- **L3 nit, DAV URLs in release logs.** sync/SyncCoordinator.java:1345, 1394 and 1417 log the book or calendar URL at `Log.w`, which R8 keeps.

## What

- Notifications: `setVisibility(VISIBILITY_PRIVATE)` and a public version reading "N new messages" on each mail notification and its summary; the channel named by the account's label rather than its address (the channel id kept, so the user's channel settings survive).
- An optional "Hide in recents" setting: `setRecentsScreenshotEnabled(false)` from API 33, FLAG_SECURE below.
- SecureStore: `cipher.updateAAD(purpose)` per purpose ("accounts", "oauth-grant"); a blob that opens only without associated data is opened once that way and sealed again with it.
- MailSession: verbs and `close` serialized (`synchronized`, or the handle swapped through an `AtomicLong` in release), so close waits for a running verb.
- Phone pass: a guard that stops a pass deleting more than a share of a book's or calendar's rows (`SyncResult.tooManyDeletions`, the user asked before it goes on); a book the user does not own written with `AGGREGATION_MODE_DISABLED`.
- Adapters: `super(context, false)`.
- SyncCoordinator: log the collection without its URL.
