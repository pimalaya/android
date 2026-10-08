---
cairn: tasks
change: google-sync
---

# Tasks

In order; 1, 2 and 5-A1 are small and can land first, together.

## 0. Before tuning
- [ ] Read the Gmail API quotas of project 991810147220 (per-minute per-user, per-day) in the Cloud console
- [ ] Check live on `google@pimalaya.org` whether `history.list` records carry `labelIds`

## 1. One consent
- [x] One authorization request with the union of the chosen domains' scopes; one refresh token stored for the account
- [x] A domain added later re-asks with the union (incremental authorization)
- [x] Test: the flow asks once for mail, calendar and contacts

## 2. Per-minute quota
- [x] Throttle recognises the per-minute quota 403 (`Quota exceeded for quota metric`, `Units per minute per user`) and 429; waits to the next minute without `Retry-After`, bounded
- [x] Pacing bucket gains a per-minute unit budget beside the per-second rate
- [x] Tests: a 403 quota answer waits then succeeds; the budget caps a long pass

## 3. One account listing
- [x] Gmail listing without `labelIds`, `includeSpamTrash=true`, 500 a page, newest first, cursor = page token
- [x] One envelope cache per account, shared across sessions, emptied each pass
- [x] Placement of each mail in every mailbox its `labelIds` name; Trash/Spam take a mail out of its other mailboxes
- [x] One unfiltered history per account per pass, `maxResults=500`, `messagesDeleted` vanished directly
- [x] One floor per account; first sync = newest 50 of the account + newest 50 of the Inbox when behind; scroll and fill widen the account listing
- [x] `MailPool` for Gmail: listing on one session, metadata over the pool
- [x] Tests: a mail under three labels read once and placed three times; archive and label changes move placements; a trashed mail leaves its labels; request counts against today's per-label sync

## 4. Batched metadata
- [x] io-gmail: batch coroutine (`/batch/gmail/v1`, multipart/mixed, 50 per batch), inner answers parsed per part
- [x] Bridge: metadata reads 50 per batch; inner 429/quota retried alone; 404 = gone
- [x] Tests: batched read equals single reads; partial throttling; request counts

## 5. Audit items
- [x] A1: People expired token (410, or 400 "Sync token is expired") falls back to a full round; optionally io-gpeople keeps `status` and `details[].reason` (io-gpeople commit f54c67b, unreleased)
- [ ] A1 follow-up: once io-gpeople is released, bump it and match `GpeopleSendError::is_sync_token_expired` in `sync_google_cards` instead of the message text (TODO in rust/src/client/google.rs)
- [ ] B6: People pages of 1,000; account delta read once per pass, projected per group
- [ ] B2: Google Calendar with `syncToken`, series folding, 410 fallback

## 6. Land
- [ ] Measure on the device with the seeded account: first dialog, whole account, a quiet pass; compare with 2026-10-07 (SENT 64 in 11.2 s, INBOX 120 in 24.1 s, quota refusal)
- [ ] Fold into cairn/spec/mail.md, calendar.md, carddav-sync.md, onboarding.md; log entry; CHANGELOG; docs/performance.md
