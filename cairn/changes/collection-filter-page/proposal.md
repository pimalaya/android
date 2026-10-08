---
cairn: change
id: collection-filter-page
status: draft
created: 2026-10-08
---

# A filter page by account, roles for direct access, a default collection

## Why

The filter (`MergedFilter`, shared by the three domains) is a checkbox dialog, in memory only, reset on restart. Mail lists mailboxes by name, deduped across accounts, so unticking "INBOX" hides every account's inbox and two accounts' "Archive" are one row; calendars and books list by name with no account, so two "Personal" calendars look alike. There is no quick way to see only one kind of mailbox, the trash say, across accounts.

No collection is a default: a new contact or event asks every time there is more than one (`addContact`, `composeEvent`, MainActivity.java:2191, 2233), read-only calendars offered too. pimdir already has the role (`default` for `text/calendar` and `text/vcard`, STORAGE §4.3) but the bridge never sets it.

## What

### A page, two levels
- The filter opens a full page per domain: each account with a checkbox, its collections indented below with theirs. The account checkbox ticks or unticks all of its collections, and reads partly ticked when some are; unticking an account keeps its collections' own choices for when it comes back.
- Keyed by collection id, persisted across restarts (per domain).
- Checkboxes, not switches: the drawer's "deactivated" pill means an account does not sync, which the view filter never changes.

### Roles for direct access
- Mail: role chips above the list (Inbox, Sent, Drafts, Trash, Junk, Archive, All), narrowing what the filter shows to the collections holding that role. "Trash" is every shown account's trash. The name-merged mailbox rows go: the chip replaces them.
- Contacts and calendars: a Default chip, the default collection of every shown account.

### Default collections
- The bridge sets pimdir's `default` role from what the source states: JMAP `isDefault` (calendars, RFC 9610 address books), Graph `isDefaultCalendar` and the default contacts folder, Google's `primary` calendar and the `myContacts` book.
- CalDAV states it only through RFC 6638 `schedule-default-calendar-URL`, which io-webdav does not read yet; CardDAV never. There the app falls back to the account's only writable collection of that kind, else to the user's choice: "Set as default" on a collection in the filter page, kept by the app, never written to pimdir (the store's role is what a source states).
- A new contact or event goes to the default of the account in view; the picker shows only when no default exists, writable collections only, preselecting the default.

## Out of scope

Saved filters, a default for mail. CalDAV RFC 6638 in io-webdav (a later io-webdav change; until then the fallback).
