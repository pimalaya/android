---
cairn: change
id: phone-contacts-mirror
status: active
created: 2026-10-09
---

# Phone contacts: an explicit option, kept in step by events rather than syncs

## Why

The store is the truth, in vCard; the phone's `ContactsContract` is a view of it, and an editable one, which is why it is reconciled as a second source (`phone`, beside `server`) with the same three-way merge (docs/phone-sync-plan.md, docs/contacts-mapping.md). That design stays. What is blurry is everything around it:

- **Nobody chooses it.** The standard setup subscribes every book with phone mirroring on and asks the contacts permission with no word of why (`confirmAllBooks` → `commitBooks`); the advanced one does the same behind the books page (`setBookState(url, on, on, on)`).
- **It reads as a second sync.** Besides the phone, server, phone passes each book already runs, every pull and the drawer's sync run a separate phone pass (`runner.syncLocal`), which asks the contacts permission at pull time and reports its own "Local" toast line.
- **Contacts-app edits wait for us.** The adapter declares `supportsUploading`, but `Accounts.reconcile` sets `setSyncAutomatically(false)`, so Android never runs it on an edit: a change made in the Contacts app reaches the store at our next sync only.
- **Our writes wait for a sync.** An in-app edit, an import or a resolved conflict reaches the Contacts app at the next sync's phone pass.

## What

### The option

- **Standard setup:** under the Contacts card of the sign-in page, a switch "Also in the phone's Contacts app", line "The dialer and messaging apps see them, and edits there come back here.", on by default.
- **Advanced setup:** the same switch under the Contacts section's card. The books page loses nothing and gains nothing: it picks books, the switch applies to every one ticked.
- **Account settings:** per book, "Show in the phone's contacts", as today.
- The contacts permission is asked when the setup continues with the switch on, or when the settings switch is turned on, and nowhere else. Refused, the switch goes back off and the setup carries on.

### Already on this phone

A Gmail address is often signed in on the phone itself (the Play Store wants a Google account), with Google's own sync filling the Contacts app with the same people; mirroring ours beside them shows everyone twice. Once the permission is granted, the setup looks for raw contacts of the same address under another account type (`RawContacts.ACCOUNT_NAME` = the address, `ACCOUNT_TYPE` not ours: `com.google`, DAVx5's `bitfire.at.davdroid`, and the like). Found, the account's books are not mirrored, and the result page and the settings say "Already in the phone's contacts through another app"; the settings switch can still turn mirroring on. No hint to read before the fact: the app looks, and says what it saw. The check is one provider query once the permission is granted (agreed 2026-10-09, provided it stays that cheap).

### Kept in step by events

- **Store to phone:** every write to a mirrored book (an edit, a create, a delete in the app, a server pass, a resolved conflict, an import) queues that book's phone pass on the io executor, a second later so a burst coalesces into one. No network; the book's own lock (`OfflineEngine.syncLock`) serializes it against the adapter and the background run, and the process's `SyncLock` is not taken, so the mirror keeps working while a background sync runs.
- **Phone to store:** `setSyncAutomatically(true)` on our accounts, so a Contacts-app edit makes Android run `SyncService` after its own short batching, which runs the phone pass alone, offline. The edit then waits in the store for the next sync, in the app or in the background, like any edit made offline. Our own writes carry `CALLER_IS_SYNCADAPTER` and never trigger it.
- **On return:** the app, coming to the foreground, runs the phone pass of every mirrored book whose raw contacts changed (`PhoneRemote.changed`, one count), so the list shows a Contacts-app edit at once, Android's batching or its global auto-sync switch notwithstanding.
- **Server sync:** each book's phone, server, phone passes stay. The separate `runner.syncLocal` after a pull or a drawer sync goes; the background run keeps one, offline, as the last net.

### The switch drives the Android account

- On: permission, then `Accounts.reconcile` creates the book's account, then the first projection runs, on the strip ("Writing N contacts to the phone").
- Off: one last phone pass (a Contacts-app edit not yet ingested would be lost), then the account is removed, which removes its raw contacts; the contacts stay in the store.
- Reconcile moves to where the set changes: this switch, startup, account removal. It leaves the phone pass.

### Removed

- The "Local" line of the sync report, the `syncLocal` adb hook and the in-app `syncLocal()`.
- The contacts permission request at pull time.
