---
cairn: tasks
change: production-release
---

# Tasks

## 0. Started now: what waits on others

- [ ] Privacy policy on pimalaya.org, matching PRIVACY.md (Google's verification and Play's Data safety form both ask for it)
- [ ] Google: restricted-scope verification for `https://mail.google.com/` submitted (privacy policy, demo video, Search Console for pimalaya.org, yearly renewal); until then 100 users and 7-day refresh tokens in Testing
- [ ] Play developer account: a personal account created after 2023-11-13 needs a closed test of at least 12 testers for 14 days in a row before production access, an organisation account a D-U-N-S number; the pre-v1 builds run that test

## 1. Data loss and correctness

Restated against the code on 2026-11-06. Gone since the 2026-10-09 review: the IMAP body filed under another mailbox's message (437be93: bodies are shared by link id only on Gmail and JMAP, `MailEngine.lookup`), the Gmail IMAP first step listing a whole account (437be93, 3d999f8: each ticked mailbox from its own floor), the sends repeated on an ambiguous failure (write-once-and-oauth-dos: *May have been sent*, `Message-ID` checks before an IMAP COPY fallback or APPEND, Graph `transactionId`, no resend after a timeout), Gmail trash and junk unlisted on a fresh device (dropped from the spec on 2026-10-12, mail-filter-scope).

- [x] A session closed under a running verb: `WorkerPool` returns when its wait is interrupted (sync/WorkerPool.java:201-207) and `MainActivity.onDestroy` interrupts it (`shutdownNow`), so a rotation during a mail pass frees a native session a worker still uses. The wait uninterruptible, the interrupt restored after; `MailSession` verbs, `close` and `reopen` synchronized (audit-s1-residuals I6, whose MailPool is gone)
- [ ] The phone pass deletes upstream whatever another app deletes, with no guard, and purges the phone row before the store records the deletion (contact/PhoneRemote.java:154-160, calendar/CalendarRemote.java), so a crash loses it. A guard in `PhonePass.enumerate` against the store's member count, held deletions left as DELETED rows, the app's own prompt, the purge after the run lands (phone-deletions-held, built 2026-11-06, device check owed; the aggregation half stays in audit-s1-residuals I7)
- [ ] Gmail: a message sent to oneself arrives read: the staged Sent copy carries `\Seen` (mail/MailSubmit.java:121) and Gmail's flags are per message, so the push marks the inbox copy read. The copy staged with no flag set where the provider files it (Gmail, Graph), its state pulled (e2e-suite)
- [ ] IMAP: a staged copy's origin is found again by link id across every collection of the source (rust/pimdir/queries/app/bound_elsewhere.sql, `LIMIT 1`), so a copy can take another mailbox's message under the same UID; attachment stand-ins keyed by account and UID (FileStore.java) can badge another mailbox's parts. The origin pimdir stages kept with the create, stand-ins keyed by collection and link id (no change yet)
- [ ] Repeated writes left after M6: a send repeated when the app dies between the handover and the acknowledgement (mail.md says to), a plain IMAP COPY or Graph copy rerun after a lost answer, creates without an idempotency key rerun after one (Graph contacts, Google People, Google Tasks, Microsoft To Do, JMAP cards and events) (network-trust-hardening, whose Open M6 and L1 are stale)
- [ ] Gmail: trash or junk ticked at the first step lands empty as a closed round (mail/GmailStep.java:84) and no later step fills it; the first step leaves them to a round of their own (mail.md, e2e-suite)
- [ ] Gmail: a message under a label the roster does not know yet stays in All Mail alone, unreported, its body downloaded twice; the roster re-read once and the rest counted as unfiled. The Graph and JMAP steps it also names are dead code since 3d999f8, to delete (a-message-is-filed-somewhere, restated; fill-from-two-queues)
- [ ] IMAP without SORT: the floor sample stops early on imports appended in rising date order (rust/src/mail/imap.rs:572) and lists every envelope back to their dates; counted with `UID SEARCH SENTSINCE` instead. mail.md still says 50 and one account floor while the code follows fill-from-two-queues (fold owed)
- [ ] A null byte array or string from Java turns into an empty one at the bridge (`unwrap_or_default` in rust/src/ffi/offline.rs and ffi/mail.rs, `read_string`): unreachable today, an empty SMTP send if it ever is; an error reply instead
- [ ] To check: a Gmail 403 rate limit is parked as refused for good (`refused_by_api`, rust/src/mail/session.rs); a Google Calendar insert rerun under the same iCalUID answers 409 on every pass

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
- [ ] Microsoft: the production Entra client in place of the testing one; publisher verification for tenants that require it
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
- [ ] Store performance follow-ups: agenda prefilter, contacts rows from summaries, refcounts, large attachments streamed (store-performance-followups)
- [ ] The filter panel's leftovers: mail Starred, Answered and Received date, the Files filter (one-filter-panel)

## 7. Store release

- [ ] Storage gate: the production track and F-Droid only on a storage-v1.0.0 store (pimdir's owner fence decided, io-pimdir released at v1, the app following it); until then Play closed testing and GitHub pre-releases
- [ ] Play: fixed-price listing, payments profile, EU trader declaration (it shows an address publicly), Data safety form, screenshots light and dark
- [ ] F-Droid: fastlane metadata, the recipe building Rust and the NDK, reproducible to keep the upstream signature
- [ ] GitHub release: signed APKs per ABI and universal, CHANGELOG section cut for the version
- [ ] Release checklist run on a clean phone: setup on Google, Microsoft, Fastmail and an IMAP and DAV host, a day of background sync, offline use
