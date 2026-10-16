---
cairn: log
change: phone-account-types
landed: 2026-10-16
---

# Calendar accounts leave the Contacts app, removals keep phone edits

Capabilities moved: calendar (ADDED Calendar accounts stay out of contacts apps), carddav-sync (ADDED Removing a phone account never drops a phone edit).

Found on a device after a fresh setup: the Contacts app listed the calendar account as a second, empty contacts account, since books and calendars shared the `org.pimalaya` type and contacts apps list every account of a type with a contacts adapter, syncable or not. A contact saved there never synced: Android never asked a non-syncable account, and `SyncService` returned silently on an account with no book URL. Deleting the account and setting it up again then removed the Android account with the contact in it.

Calendar accounts now take `org.pimalaya.calendar`, with `CalendarAuthenticatorService` and the calendar adapter alone. `CalendarRows.reconcile` runs the phone pass of an earlier build's calendar accounts, then `Accounts.reconcileCalendars` removes them and recreates them under the new type, projected whole; `CalendarRemote.target` reads both types for that last pass. `SyncService` warns on an account with no book. Removing a book's account (`Accounts.purge`, behind `reconcile` and `remove`) keeps it while its raw contacts hold a creation or an edit not yet in. Deleting an account runs its books' phone pass first (`SyncCoordinator.lastPhonePass`) and removes nothing when one fails; a phone switch off keeps the account when its last pass fails.

Proposal agreed in-session before the code. JVM tests: AccountsTest gains the migration of an earlier build's calendar account and loses the book-never-syncs-calendars check, which no longer has an adapter to ask; HardeningTest checks each adapter against both types. The unsynced-edit guard is not covered: Robolectric has no contacts provider. The migration was not run on a device.
