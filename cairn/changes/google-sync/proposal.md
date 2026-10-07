---
cairn: change
id: google-sync
status: active
created: 2026-10-07
---

# A Google account signs in once and syncs Gmail as one account listing

## Why

Seen on the owner's device on 2026-10-07, connecting the seeded Google Workspace test account (`google@pimalaya.org`: 400 mails over 18 months, every one carrying a `moa-seed` label beside INBOX, SENT or a `Seed/…` label; 40 contacts; 75 events).

- **Two consent screens.** The connection asked Google twice, in two browsers: `scope=https://mail.google.com/` first, then calendar and contacts. Microsoft asks once for every domain ticked.
- **Slow, and bound by latency rather than quota.** SENT 64 mails in 11.2 s, INBOX 120 in 24.1 s, `moa-seed` 350 in 78.4 s: about 5 mails a second per session, 2 sessions (`MailPool.GMAIL`). Each mail's metadata is one `messages.get` after the other at about 180 ms; the pacing allows 40 a second.
- **Each mail read once per label.** Gmail files a mail under several labels at once (here every mail is in `moa-seed` and in its INBOX/SENT/`Seed/…` label; on real accounts `IMPORTANT` and `CATEGORY_*` overlap everything). The app syncs each label as its own mailbox, the two sessions keep separate envelope caches, so a mail's metadata is fetched once per label holding it.
- **A per-minute quota refused, and not waited out.** `Gmail API returned HTTP 403: Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user' … project_number:991810147220`. INBOX, Seed/Clients and Seed/Factures failed outright: the throttle (`rust/src/client/throttle.rs`) knows the reasons `rateLimitExceeded` and `userRateLimitExceeded`, not this one. By count the pass spent roughly 1,000 to 2,000 units in that minute, against the 15,000 units per minute per user Google documents: either the new project's quota is lower, or the fill and a pass overlapped. To read in the Cloud console (project 991810147220, Gmail API, Quotas) before tuning.

The read-only audit of the Google side (2026-10-07) found the rest: an expired People sync token never recognised (A1), trashed or spammed mail kept in its labels by deltas (A2), envelope caches outliving their freshness in fill sessions (A3), the whole history replayed per mailbox (B4), Google Calendar listed in full on every pass (B2), People pages of 100 read once per contact group (B6).

## What

### 1. One consent for every domain ticked
One authorization request carrying the union of the scopes of the domains chosen (mail, calendar, contacts), one browser round, one refresh token, as for Microsoft. A domain added later asks again with the union (Google's incremental authorization keeps earlier grants).

### 2. Gmail's per-minute quota is a throttle
- A 403 whose message names a quota (`Quota exceeded for quota metric …`, `Units per minute per user`) or whose reason is `rateLimitExceeded`/`userRateLimitExceeded`, and a 429, wait rather than fail: until the next minute when Google gives no `Retry-After`, bounded as the other waits are.
- The pacing bucket keeps its per-second rate and gains a per-minute budget, so a long pass never runs past the minute's units.

### 3. Gmail is one account listing; labels are rebuilt locally
- **One listing per account:** `messages.list` with no `labelIds` and `includeSpamTrash=true`, 500 ids a page, newest first. "All Mail" is not a label: it is every mail, whatever its labels; Spam and Trash join through `includeSpamTrash`.
- **Each mail read once:** its metadata (`format=metadata`, the summary headers, `Content-Type`) gives its `labelIds`; one envelope cache per account, shared by every session, emptied at the start of each pass (fixes A3).
- **Labels stay mailboxes:** each mail is placed in every mailbox its labels name (a pimdir item placed in several collections), with no request per label. Archiving is `INBOX` leaving the label set; a label added places the mail in another mailbox; a mail in Trash or Spam leaves its other mailboxes (fixes A2, as neverest's `belongs()` does).
- **One history per account:** `history.list` unfiltered, `maxResults=500`, once per pass, from the account's `historyId`; `messagesDeleted` are vanished directly, with no read ending in 404; touched ids read once (fixes B4).
- **Chunks and floors as today:** the first sync takes the newest 50 of the whole account, plus the newest 50 of the Inbox when the Inbox is behind, so the dialog never opens on an empty Inbox; scroll and background fill widen the single listing by count, the date of the oldest kept as the floor. The per-label floor of today becomes one floor per account.
- `MailPool` for Gmail: the listing on one session, metadata reads spread over the pool under the shared bucket.

### 4. Metadata read 50 at a time
- io-gmail gains a batch coroutine for `POST https://www.googleapis.com/batch/gmail/v1` (multipart/mixed, up to 100 calls; Google advises 50 or fewer for Gmail). Each inner call is still billed its units: the batch saves round trips, not quota.
- Inner 429 or quota answers are retried alone through the throttled path; a 404 is a mail gone since the listing.
- Expected: the rate stops being 5 a second per session and becomes the quota's.

### 5. The audit's other Android items
- **A1, now:** an expired People sync token is a 410, or a 400 whose text says the token expired (neverest's `is_expired`); the round falls back to a full listing instead of failing every pass. Better still, io-gpeople keeps `error.status` and `details[].reason` so callers match the code, not the text.
- **B6:** People pages of 1,000 (the API's ceiling, not 100); the account-wide delta read once per pass and projected onto each contact group, not relisted per group.
- **B2:** Google Calendar with its `syncToken` (same parameters every time: `showDeleted`, `maxResults` 2500), changed instances folded into their series, a 410 falling back to today's full listing that builds bodies.

## Out of scope
- MOA and neverest: their own plan (`moa/docs/plan/google-sync.md`).
- The calendar and address-book row in the creation form (postponed by the owner).
- A second Google OAuth client for release builds (the debug key's SHA-1 is registered; a release build signed with `cardamum.jks` needs its own client).

## Open questions
- The Gmail per-minute quota of project 991810147220: the documented default or lower? Read before setting the per-minute budget.
- Does a history record carry the mail's `labelIds` (to skip a metadata read on a label change)? To check live on the test account.
- First sync with labels: is "newest 50 of the account plus newest 50 of the Inbox" enough, or should Sent join the dialog too?
