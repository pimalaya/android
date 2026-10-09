---
cairn: change
id: quiet-first-sync
status: abandoned
created: 2026-10-08
---

# A quiet first sync: inbox and defaults in the background

Opened for discussion after `local-first-actions`, `deleted-items` and `collection-filter-page`.

## Why

Standard onboarding is meant to be fully automatic and has not been tested end to end; advanced was (2026-10-08, satisfactory). The first sync today runs behind a non-cancelable modal (`showSyncDialog`, MainActivity.java:1020) and syncs every mailbox (newest 50 each, `firstMail`, MainActivity.java:1626), every subscribed book and every calendar, then the fill widens every mailbox by 500. A new user waits on a dialog for mailboxes they may never open.

## What (to discuss)

- **Standard onboarding** tested end to end per provider (Posteo, Fastmail, Gmail, Outlook.com, a plain IMAP/DAV host); gaps fixed.
- **No modal on the first sync.** It runs in the background, the list filling as it lands; the modal stays for a sync the user starts.
- **Only the inbox and the defaults first.** Mail syncs the inbox alone (`firstMail` passes a scope instead of `null`, the per-mailbox skip at MainActivity.java:1408 already exists); contacts and calendars their default collections (`collection-filter-page`). Everything else is listed (roster) but not synced, and starts unticked in the filter.
- **The filter drives the sync.** Ticking a collection syncs it (the filter's `onChange`, MainActivity.java:2853, today only re-renders); the fill (`MailFill.next`) covers shown collections only.

## Open questions

- Unticked collections: synced in the background later, or never until shown?
- The fill's scope on metered networks, and with `mail-offline-policy`.
- A collection unticked after syncing: keep its data (pimdir never deletes) or offer to forget it.
