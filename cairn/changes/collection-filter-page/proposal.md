---
cairn: change
id: collection-filter-page
status: active
created: 2026-10-08
---

# A filter page by account, roles for direct access, a default collection

## Why

The filter (`MergedFilter`, shared by the three domains) is a checkbox dialog, in memory only, reset on restart. Mail lists mailboxes by name, deduped across accounts, so unticking "INBOX" hides every account's inbox and two accounts' "Archive" are one row; calendars and books list by name with no account, so two "Personal" calendars look alike. There is no quick way to see only one kind of mailbox, the trash say, across accounts. The same filter doubles as the account's on and off: hiding an account in it is what the drawer reads as deactivated.

No collection is a default: a new contact or event asks every time there is more than one (`addContact`, `composeEvent`, MainActivity.java:2191, 2233), read-only calendars offered too. pimdir already has the role (`default` for `text/calendar` and `text/vcard`, STORAGE §4.3) but the bridge never sets it.

## What

### The sync model
- The drawer lists every account. An account's settings switch it on or off; an account that is off syncs nothing (the drawer's sync, a list's pull, a first sync, the mail fill), no filter offers it and no list shows its items. Its data stays stored. The phone's mirror of its books is local and left alone.
- "Sync all" in the drawer syncs every collection of every account that is on.
- In a domain tab, the filter hides from the listing and excludes what it hides from that tab's pull.

### A page, two levels
- The filter opens a full page per domain: each account that is on with a checkbox, its collections indented below with theirs (the outbox first under a mail account, the on-device book as its own entry on contacts). The account checkbox reads ticked, partly ticked or unticked; unticking it keeps its collections' own choices for when it comes back, ticking it back restores them (all of them when they tick none); ticking a collection of a hidden account shows the account with that collection alone. Reset shows everything.
- Keyed by collection id, persisted across restarts per domain. A store with nothing kept (every earlier build) shows everything.
- Checkboxes, not switches: the drawer's on and off decides what syncs, which the view filter never changes.

### Roles for direct access
- Mail: role chips above the list, Inbox, Sent, Drafts, Trash, Junk, Archive, at most one on; no chip on narrows nothing (no "All" chip). A chip narrows what the filter shows to the collections holding that pimdir role. "Trash" is every shown account's trash. The name-merged mailbox rows go: the chip replaces them.
- Gmail is synced as one account listing, its labels being placements: its system labels carry the roles (INBOX, SENT, DRAFT, TRASH, SPAM as inbox, sent, drafts, trash, junk, set by the bridge's label listing). Gmail has no archive label (archived mail is mail without INBOX, listed under no label), so Archive shows none of a Gmail account's mail.
- Contacts and calendars: a Default chip, the default collection of every shown account.

### Default collections
- The bridge sets pimdir's `default` role from what the source states: JMAP `isDefault` (calendars, RFC 9610 address books), Graph `isDefaultCalendar` and the default contacts folder (the one Graph serves outside the folder list), Google's `primary` calendar and the `myContacts` book. It also says which collections the user may not write into (JMAP `myRights`, Graph `canEdit`, Google `accessRole`), kept by the app since pimdir has no column for it.
- A contacts sync lists the account's books first and restates both, so an account connected before this change gains its default without being re-added.
- CalDAV states it only through RFC 6638 `schedule-default-calendar-URL`, which io-webdav does not read yet; CardDAV never. There the app falls back to the account's only writable collection of that kind, else to the user's choice: "Set as default" on a collection in the filter page, kept by the app, never written to pimdir (the store's role is what a source states).
- A new contact or event goes to the default of the account in view (the one account the filter shows) without asking; otherwise the picker shows, writable collections only, the default preselected, a single writable one taken without asking.

## Out of scope

Saved filters, a default for mail. CalDAV RFC 6638 and RFC 3744 privileges in io-webdav (a later io-webdav change; until then the fallback, every DAV collection read as writable).
