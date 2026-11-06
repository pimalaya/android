---
cairn: tasks
change: production-release
---

# Tasks

## 0. Started now: what waits on others

- [ ] Privacy policy on pimalaya.org, matching PRIVACY.md (Google's verification and Play's Data safety form both ask for it)
- [ ] Google: restricted-scope verification for `https://mail.google.com/` submitted (privacy policy, demo video, Search Console for pimalaya.org, yearly renewal); until then 100 users and 7-day refresh tokens in Testing (oauth-verification)
- [ ] Play developer account: a personal account created after 2023-11-13 needs a closed test of at least 12 testers for 14 days in a row before production access, an organisation account a D-U-N-S number; the pre-v1 builds run that test

## 1. Data loss and correctness

Restated against the code on 2026-11-06. Gone since the 2026-10-09 review: the IMAP body filed under another mailbox's message (437be93: bodies are shared by link id only on Gmail and JMAP, `MailEngine.lookup`), the Gmail IMAP first step listing a whole account (437be93, 3d999f8: each ticked mailbox from its own floor), the sends repeated on an ambiguous failure (write-once-and-oauth-dos: *May have been sent*, `Message-ID` checks before an IMAP COPY fallback or APPEND, Graph `transactionId`, no resend after a timeout), Gmail trash and junk unlisted on a fresh device (dropped from the spec on 2026-10-12, mail-filter-scope).

- [x] A session closed under a running verb: `WorkerPool` returns when its wait is interrupted (sync/WorkerPool.java:201-207) and `MainActivity.onDestroy` interrupts it (`shutdownNow`), so a rotation during a mail pass frees a native session a worker still uses. The wait uninterruptible, the interrupt restored after; `MailSession` verbs, `close` and `reopen` synchronized (audit-s1-residuals I6, whose MailPool is gone)
- [ ] The phone pass deleted upstream whatever another app deleted, and erased the phone row before the store recorded it. Built 2026-11-06 (5cf0f15, phone-deletions-held): more than half of at least 20 held, a dialog, rows erased after the round. Owed: the device check, and a fake ContactsContract provider so the contacts spoke is tested as the calendar one is; the aggregation half stays in audit-s1-residuals I7
- [x] Gmail: a message sent to oneself arrives read: the staged Sent copy carries `\Seen` (mail/MailSubmit.java:121) and Gmail's flags are per message, so the push marks the inbox copy read. Fixed: the staged copy takes the markers of the provider's own before landing (cairn/log/2026-11-06-sent-copy-takes-provider-markers.md)
- [x] IMAP: a staged copy's origin was found again by link id across every collection of the source, so a copy could take another mailbox's message under the same UID; the statement now follows pimdir's `origin_for_link` within the account and the `Message-ID` (cairn/log/2026-11-06-a-copy-from-its-own-mailbox.md)
- [ ] IMAP: attachment stand-ins are keyed `part:<link id>#<section>` and listed by account and link id (pimdir STORAGE §14.3, `list_attachments`), and an IMAP link id is a bare UID every mailbox reuses, so the reader can badge another mailbox's parts. The store itself holds one stand-in for two messages sharing a UID, so no reader-side fix can separate them. A decision first, in a cairn change: the IMAP link id the `Message-ID`, an identity pimdir's model assumes, falling back to UIDVALIDITY and UID for a message without one or sharing one in its mailbox; bodies stay unshared on IMAP (a Sent copy and its delivery share a `Message-ID`, not their bytes); stores already written re-list their IMAP mail rather than reinstall
- [ ] Repeated writes left after M6: done, a send the app stopped in parked uncertain (cairn/log/2026-11-06-a-send-the-app-stopped-in-waits.md) and a copy skipped when its target holds the message (cairn/log/2026-11-06-a-copy-is-not-made-twice.md); left, creates without an idempotency key rerun after a lost answer (Graph contacts, Google People, Google Tasks, Microsoft To Do, JMAP cards and events). Plan: a create noted on the device before its push, as the outbox now does, and one found noted or failed without an answer looked up by its UID on its next push: JMAP by a `uid` filter, Google Calendar by `iCalUID` (its 409), Graph contacts by the stash's extended property; Google People, Google Tasks and To Do have no lookup by UID found yet. Each lookup checked live on the test accounts (network-trust-hardening, whose Open M6 and L1 are stale)
- [x] Gmail: trash or junk ticked at the first step landed empty as a closed round (mail/GmailStep.java:84) and no later step fills it; the first step leaves them to a round of their own (cairn/log/2026-11-06-gmail-trash-opens-by-a-round.md)
- [x] IMAP without SORT: the floor sample stopped early on imports appended in rising date order and listed every envelope back to their dates; now checked by the round's search and a halving by day (cairn/log/2026-11-06-the-floor-checked-without-sort.md). mail.md still says 50 while the code follows fill-from-two-queues' 25 (its fold owed)
- [x] A null byte array from Java turned into an empty one at the bridge (`unwrap_or_default` in rust/src/ffi/offline.rs and ffi/mail.rs): unreachable today, an empty SMTP send if it ever was; now an error reply, and `PimdirSql.hash` throws on it. `read_string` still reads a null string as empty
- [x] A send Gmail refused for a rate or a quota (a 403) was parked as refused for good; the throttle now marks the exchange `limited` and the row stays pending (cairn/log/2026-11-06-a-send-refused-for-a-rate-stays-queued.md)
- [ ] To check: a Google Calendar insert rerun under the same iCalUID answers 409 on every pass, read back by `iCalUID` and bound instead, after a live check of the 409

## 2. Device checks, then fold and archive (owner)

- [ ] Device check of the changes waiting on it alone, on one release-candidate build against one checklist gathered from their tasks: account-settings-page, attachments-as-files, attendee-notifications, automatic-references, background-check, calendar-conflict-form, calendar-occurrence-writes, calendar-zones-and-series, files-review-fixes, files-tab, filter-page-account-hides, gmail-first-sync-stall, item-links, jmap-calendar-writes, jmap-release-readiness, mail-download-window, mail-filter-apply, mail-filter-scope, mail-sync-robustness, new-task-page-and-drop-in, phone-contacts-mirror, redesign-for-the-palette, sync-dock, sync-feedback-pinned, sync-strip-progress, task-batch-actions, tasks-in-google-and-microsoft, write-once-and-oauth-dos
- [ ] Device check of the changes with work left besides: fill-from-two-queues, mail-load-more, mail-load-more-graph, mail-load-more-imap, mail-load-more-jmap, one-onboarding-path, phone-calendar-mirror, sync-strip-in-app-bar (assembleDebug never run), crash-and-surface-hardening (OAuth loopback listener)
- [ ] Live checks on the Google test account (google-sync: history labelIds, batch host, iCalUID listing, People token and deletions, the device measure)
- [ ] Each checked change folded into the spec, logged and archived
- [ ] Archive the changes already done: body-loss-races, body-run-loop, drop-sync-mark, google-delete-loss, mailbox-refusal-skipped, oauth-pending-grant-encrypted, store-performance (fold and log first if missing), gtd-hardening (landed), ical-rs-0-6 (fold)
- [ ] Archive bundled-sqlite as superseded: platform-sqlite (landed 2026-11-01) put the store on the platform's SQLite and dropped requery

## 3. Released libraries and supply chain

- [x] io-http 0.6.0, ical-rs 0.7.0, io-imap 0.8.1, io-webdav 0.7.0, io-jmap 0.7.0, io-pim-discovery 0.11.0 released and pinned from crates.io (checked 2026-11-06)
- [ ] The three git sources left in rust/Cargo.toml released and dropped: io-pimdir (the pinned b25523d is storage-draft-09, after the v0.9.0 tag 42a2f26; the bridge first follows 5c18a02, the search zone the CLIs moved to on 2026-11-06), io-msgraph past v0.7.0 for the To Do surface (01105a5), io-gtasks 0.1.0 first published; release-pinned-libs/tasks.md restated (it still names io-pimdir 0.8.0 and leaves the done releases open)
- [ ] A body bound (`max_body_len`) on every HTTP call, none in rust/src as of 2026-11-06 (release-pinned-libs)
- [ ] CI builds unsigned and signs on `v*` tags only, in a protected environment (release-supply-chain)
- [ ] Actions pinned by SHA, least `permissions`, a read-only Cachix token outside releases (release-supply-chain)
- [ ] Gradle verification metadata, a Gradle wrapper or F-Droid srclib (release-supply-chain)
- [ ] `cargo deny` with yanked denied, no git sources, a weekly audit run; a release dry run on a fork (release-supply-chain)
- [ ] Versioning: versionCode and versionName from the tag; distinct codes per ABI for the GitHub APKs and F-Droid, Play taking the one bundle .github/workflows/releases.yml already builds (android/app/build.gradle.kts)

## 4. Sign-in for real users

- [ ] Google: an Android OAuth client per signing key (Play App Signing, GitHub APK, F-Droid unless reproducible), the redirect scheme in AndroidManifest.xml following
- [ ] Microsoft: the production Entra client in place of the testing one; publisher verification for tenants that require it (oauth-verification)
- [ ] Re-sign-in: an account whose grant or password stopped working signs in again from its settings, keeping its outbox and staged edits
- [ ] Fastmail: one consent for IMAP, JMAP and DAV (one-fastmail-consent)

## 5. Security residuals

- [ ] Lock-screen notifications private, a public version saying only the count (audit-s1-residuals I1)
- [ ] "Hide in recents" setting (audit-s1-residuals I2)
- [ ] SecureStore blobs bound to their purpose as AAD (audit-s1-residuals I3)
- [ ] Sync adapters not parallel, no URL in warnings (audit-s1-residuals L1, L3)
- [ ] OAuth: issuer pairing (H3), `iss` required where advertised (M1), https-only endpoints in io-oauth (M2), RFC 8707 `resource` (L3), identity check after a grant (L4), error redirects before the state check (L5) (network-trust-hardening)
- [ ] Network: discovery redirects to other schemes or internal hosts refused (L2), TLS 1.2 floor on API 26 to 28 (L6), DoH and PACC trust documented (L8), a public-suffix rule in `trusted_href` (network-trust-hardening)

## 6. What a user meets first

- [ ] Drafts: a message being written is kept and reopened
- [ ] Attachments added in the composer, and kept on a forward
- [ ] Restoring a deleted message on every backend, not IMAP alone (DeletedItemsPage)
- [ ] Gmail: a message under a label the store does not hold yet has its body read twice, the next pass's round over the new label filing it; not a loss (a-message-is-filed-somewhere, whose Graph and JMAP halves are dead code since 3d999f8, listed for deletion in fill-from-two-queues)
- [ ] Store performance follow-ups: agenda prefilter, contacts rows from summaries, refcounts, large attachments streamed (store-performance-followups)
- [ ] The filter panel's leftovers: mail Starred, Answered and Received date, the Files filter (one-filter-panel)

## 7. Store release

- [ ] Storage gate: the production track and F-Droid only on a storage-v1.0.0 store (pimdir's owner fence decided, io-pimdir released at v1, the app following it); until then Play closed testing and GitHub pre-releases
- [ ] Play: fixed-price listing, payments profile, EU trader declaration (it shows an address publicly), Data safety form, screenshots light and dark
- [ ] F-Droid: fastlane metadata, the recipe building Rust and the NDK, reproducible to keep the upstream signature
- [ ] GitHub release: signed APKs per ABI and universal, CHANGELOG section cut for the version
- [ ] Release checklist run on a clean phone: setup on Google, Microsoft, Fastmail and an IMAP and DAV host, a day of background sync, offline use
