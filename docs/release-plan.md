# Release plan (temporary)

Gathers the work agreed on 2026-10-08 toward a first public release, after a few weeks of daily use by the owner. Each item is a cairn change under cairn/changes/<id>/ (proposal, tasks, delta) unless marked otherwise. Delete this file once the release ships.

## Order

| # | Change | Repo | Status | Blocks release |
|---|---|---|---|---|
| 0 | google-sync | android | sections 1-5 landed; device measure + fold left | yes |
| 1 | local-first-actions | android | tasks 1-6 done; task 0 (pimdir), live checks, land left | yes |
| 1b | trash-disposal-is-a-move | pimdir | draft | no (note only) |
| 2 | collection-filter-page | android | draft | yes |
| 3 | background-sync | android | deferred; manual sync only | no |
| 4 | deleted-items | android | draft | no |
| 5 | mail-offline-policy | android | draft | no |
| 6 | quiet-first-sync | android | draft, to discuss | yes (standard onboarding) |
| later | remote search | pimdir + android | not opened | no |
| later | real-time push | android + relay | not opened | no |

## 0. google-sync

Gmail as one account listing with batched reads, one Google consent, per-minute quota, People 1,000 a page with one delta per pass, Calendar from its syncToken. Left: live checks (quota of project 991810147220, history `labelIds`, batch host, iCalUID listing, People token and batchGet), device measure against SENT 64 in 11.2 s and INBOX 120 in 24.1 s, fold into spec, log, CHANGELOG. A Google account added before section 1 must be re-added to get the single consent.

## 1. local-first-actions

Every user action is a pimdir mutation, visible at once; the sync carries it out as staged, never translated.

- Task 0: io-pimdir test that a move under a minted `dup:` key (or an `alt:` key) is landed by its arrival, not pushed twice.
- Bridge wires `Move` and `Copy`.
- Push honours SYNC §4: `Remove { to }` relocates (or rejects), `Remove` deletes, `Add` with origin copies server-side, without appends. Mail and calendar today ignore `to`, mail rejects every `Add`.
- Delete = `Move` into the trash: visible in the trash at once (the Posteo report).
- Delete from the trash = permanent: IMAP `UID EXPUNGE` with UIDPLUS (marker otherwise, shown), Graph `permanentDelete`, Gmail `messages.delete`, JMAP destroy.
- Sent copy staged at send, landed by the provider's own filing or pushed as the IMAP append.
- Pending and refused marks on rows, all domains.
- Contacts create and delete through mutations instead of direct SQL.

1b. pimdir note for implementers (SYNC §7, GUIDE): a delete meant to land in a trash is a `Move`, not a `Remove`.

## 2. collection-filter-page

- Full page, accounts with tri-state checkboxes, their collections below; keyed by collection id, persisted. Checkboxes, not switches, to stay distinct from the drawer's sync on/off.
- Mail role chips (Inbox, Sent, Drafts, Trash, Junk, Archive, All) replace the merge by mailbox name; Default chip for contacts and calendars.
- Default collection: pimdir `default` role set from JMAP `isDefault`, Graph default calendar and contacts folder, Google primary and `myContacts`; fallback to the only writable collection, then the user's "Set as default" (app side). New contacts and events go there without asking.

## 3. background-sync (deferred)

No longer blocking. The release syncs manually only: background sync is removed (no periodic work, no setting; work enqueued by an earlier build is cancelled at startup).

- New-mail notifications come later, from the IMAP watcher prototype (~/code/pimalaya/IMAP_SESSION_HANDOVER_PLAN.md).
- Event reminders come after the release blockers: AlarmManager exact alarms from the local store, with USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED and POST_NOTIFICATIONS.

## 4. deleted-items

Settings > Deleted items, one list for all domains over pimdir's retained rows. Restore asks for the target collection (last one preselected) and needs a stored body; mail held as summary only says so. Rows still bound by a source show as waiting. Free space at the top: bytes, purge, collect garbage.

## 5. mail-offline-policy

Per account: bodies on open (default), bodies in the background, whole mailbox (full sync); per collection "Download this mailbox". Unmetered by default.

## 6. quiet-first-sync (to discuss)

Test standard onboarding end to end per provider. First sync in the background without the modal (kept for manual syncs), inbox and default collections only, everything else listed but unticked; ticking a collection syncs it, the fill covers shown collections only.

## Later: remote search

Local search first (summaries, then bodies stored by item 5), then a "Search on server" action. One pimdir query translated per backend (IMAP SEARCH, JMAP filters, Graph `$search`, Gmail `q`, Google Calendar `q`, People `searchContacts`, CardDAV text-match, CalDAV calendar-query). Server hits are fetched at the meta tier and stored as ordinary placements, then merged into the results. Belongs to the SEARCH spec (NLnet 2027).

## Later: real-time push (IMAP: ~/code/pimalaya/IMAP_SESSION_HANDOVER_PLAN.md)

See the push notes: provider-native Web Push (JMAP, WebDAV-Push) delivered over UnifiedPush needs no server; Gmail, Graph and Google Calendar need a credential-less relay; IMAP needs IDLE on the phone or a credentialed watcher.
