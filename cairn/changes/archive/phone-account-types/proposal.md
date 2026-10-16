---
cairn: change
id: phone-account-types
status: landed
created: 2026-10-16
---

# Phone accounts: calendars under their own type, no removal drops a phone edit

## Why

Books and calendars shared the `org.pimalaya` account type. Contacts apps list every account of a type that has a contacts sync adapter, whatever its syncable flag, so each calendar account showed as an empty contacts account. A contact saved into it was never synced: Android never asks a non-syncable account, and the adapter returned silently on an account with no book URL. Removing that account later took the contact with it.

Two removals also dropped phone edits: deleting an account moved the store's contacts to the on-device book before any last phone pass, and turning a book's phone switch off removed its account even when the last pass failed.

## What

- Calendar accounts take their own type, `org.pimalaya.calendar`, with its own authenticator and the calendar adapter alone; contacts apps no longer list them.
- Calendar accounts of the old type are migrated on the next reconcile: their rows' phone pass first, then removed, then recreated under the new type and projected whole.
- A contacts sync on an account with no book logs a warning instead of returning silently.
- Deleting an account runs its books' phone pass first and stops, keeping everything, when it fails.
- A book's phone switch off keeps its account when its last phone pass fails.
