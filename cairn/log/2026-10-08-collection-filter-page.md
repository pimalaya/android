---
cairn: log
change: collection-filter-page
landed: 2026-10-08
---

# A filter page by account, roles for direct access, a default collection

Commit 9d58842.

Capabilities moved: offline-store (added: the filter lists accounts and their collections, an account that is off takes no part, a role gives direct access across accounts, the source states a collection's default and whether it takes writes, a new contact or event goes to the default collection; modified: a list's pull syncs what it shows, the filter offers what it can hide, the bottom bar switches domains), mail (modified: a sync skips what the filter hides, the mail list narrows what it shows, a message is sent through an outbox).

**Filter.** The dialog is a page per domain (`FilterPage`): accounts with a tri-state checkbox, their collections below, Reset. `MergedFilter` is keyed by collection id and kept per domain; a store with nothing kept shows everything. Mail queries filter by collection id, so two accounts' Archive are two rows.

**Accounts on and off.** The filter no longer doubles as activation: an account's settings switch it (`AccountActivation`). An account that is off is synced by nothing, listed by no filter or list, its data and the phone's mirror kept. A tab's pull syncs what its filter shows, the drawer's every account that is on (`SyncScope`).

**Roles.** Mail role chips (Inbox, Sent, Drafts, Trash, Junk, Archive, at most one) narrow the shown collections to pimdir's role; Gmail's system labels carry theirs and Archive shows none of its mail. Contacts and calendars get a Default chip.

**Defaults.** The bridge sets pimdir's `default` role and says which collections take writes: JMAP `isDefault` and `myRights`, Graph `isDefaultCalendar`, its default contacts folder and `canEdit`, Google's `primary` calendar, `myContacts` and `accessRole`. A contacts sync restates both. The app falls back to the only writable collection, else the user's "Set as default" on the filter page (`DefaultCollection`, an app preference, never written to pimdir). A new contact or event goes to the default of the one account in view; otherwise the picker offers writable collections only, the default preselected, a single one taken without asking.

**What the implementation corrected.** The delta was revised in the same commit to what was built (the on and off moved out of the filter, no All chip, Gmail's roles, writability, the picker's rules), so it was folded as written.

**Left open.** CalDAV states no default until io-webdav reads RFC 6638 `schedule-default-calendar-URL`, CardDAV never, and every DAV collection reads as writable until RFC 3744 privileges are read.
