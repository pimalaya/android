---
cairn: tasks
change: production-release
---

# Tasks

## 1. Data loss and correctness

- [ ] IMAP: a step's body download can file another mailbox's body under a message (e2e-suite)
- [ ] Graph and Gmail items whose folder or label has no name are dropped silently (a-message-is-filed-somewhere)
- [ ] Gmail: a self-sent copy lands read in the inbox; a fresh device never lists trash or junk (e2e-suite, mail-load-more B.13 rule 7)
- [ ] IMAP first step lists 50 of 56 seeded messages on Gmail (e2e-suite)
- [ ] Sends repeated on an ambiguous failure: SMTP, JMAP, Graph, Gmail, IMAP COPY and append retries (network-trust-hardening M6)
- [ ] The phone pass's mass-deletion guard per book and calendar (audit-s1-residuals I7)
- [ ] MailSession verbs and close serialized (audit-s1-residuals I6)

## 2. Device checks, then fold and archive (owner)

- [ ] Device check of the changes waiting on it alone: account-settings-page, attachments-as-files, attendee-notifications, automatic-references, background-check, bundled-sqlite, calendar-conflict-form, calendar-occurrence-writes, calendar-zones-and-series, files-review-fixes, files-tab, filter-page-account-hides, gmail-first-sync-stall, item-links, jmap-calendar-writes, jmap-release-readiness, mail-download-window, mail-filter-apply, mail-filter-scope, mail-sync-robustness, new-task-page-and-drop-in, phone-contacts-mirror, redesign-for-the-palette, sync-dock, sync-feedback-pinned, sync-strip-progress, task-batch-actions, tasks-in-google-and-microsoft, write-once-and-oauth-dos
- [ ] Device check of the changes with work left besides: fill-from-two-queues, mail-load-more, mail-load-more-graph, mail-load-more-imap, mail-load-more-jmap, one-onboarding-path, phone-calendar-mirror, sync-strip-in-app-bar (assembleDebug never run), crash-and-surface-hardening (OAuth loopback listener)
- [ ] Live checks on the Google test account (google-sync: history labelIds, batch host, iCalUID listing, People token and deletions, the device measure)
- [ ] Each checked change folded into the spec, logged and archived
- [ ] Archive the changes already done: body-loss-races, body-run-loop, drop-sync-mark, google-delete-loss, mailbox-refusal-skipped, oauth-pending-grant-encrypted, store-performance (fold and log first if missing), gtd-hardening (landed), ical-rs-0-6 (fold)

## 3. Released libraries and supply chain

- [ ] Release io-http, ical-rs 0.7, io-imap, io-pimdir 0.8, io-webdav, io-jmap, io-msgraph, io-pim-discovery; drop every `[patch]` git entry in rust/Cargo.toml (release-pinned-libs)
- [ ] A body bound (`max_body_len`) on every HTTP call (release-pinned-libs)
- [ ] CI builds unsigned and signs on `v*` tags only, in a protected environment (release-supply-chain)
- [ ] Actions pinned by SHA, least `permissions`, a read-only Cachix token outside releases (release-supply-chain)
- [ ] Gradle verification metadata, sqlite-android pinned and checked, a Gradle wrapper or F-Droid srclib (release-supply-chain)
- [ ] `cargo deny` with yanked denied, no git sources, a weekly audit run; a release dry run on a fork (release-supply-chain)
- [ ] Versioning: versionCode and versionName from the tag, distinct codes per ABI split for Play and F-Droid (android/app/build.gradle.kts)

## 4. Sign-in for real users

- [ ] Google: an Android OAuth client per signing key (Play App Signing, GitHub APK, F-Droid unless reproducible), the redirect scheme in AndroidManifest.xml following
- [ ] Google: restricted-scope verification for `https://mail.google.com/` (privacy policy on pimalaya.org, demo video, Search Console, yearly renewal); until then 100 users and 7-day refresh tokens in Testing
- [ ] Microsoft: the production Entra client in place of the testing one; publisher verification for tenants that require it
- [ ] Re-sign-in: an account whose grant or password stopped working signs in again from its settings, keeping its outbox and staged edits
- [ ] Fastmail: one consent for IMAP, JMAP and DAV (one-fastmail-consent)

## 5. Security residuals

- [ ] Lock-screen notifications private, a public version saying only the count (audit-s1-residuals I1)
- [ ] "Hide in recents" setting (audit-s1-residuals I2)
- [ ] SecureStore blobs bound to their purpose as AAD (audit-s1-residuals I3)
- [ ] Sync adapters not parallel, no URL in warnings (audit-s1-residuals L1, L3)
- [ ] OAuth: issuer pairing (H3), `iss` required where advertised (M1), https-only endpoints in io-oauth (M2), RFC 8707 `resource` (L3), identity check after a grant (L4), error redirects before the state check (L5) (network-trust-hardening)
- [ ] Network: no resend of PUT, DELETE or POST on a timeout (L1), discovery redirects to other schemes or internal hosts refused (L2), TLS 1.2 floor on API 26 to 28 (L6), DoH and PACC trust documented (L8), a public-suffix rule in `trusted_href` (network-trust-hardening)

## 6. What a user meets first

- [ ] Drafts: a message being written is kept and reopened
- [ ] Attachments added in the composer, and kept on a forward
- [ ] Restoring a deleted message on every backend, not IMAP alone (DeletedItemsPage)
- [ ] A null JNI reply mapped to an error rather than reaching `PimalayaClient.object(null)`
- [ ] Store performance follow-ups: agenda prefilter, contacts rows from summaries, refcounts, large attachments streamed (store-performance-followups)
- [ ] The filter panel's leftovers: mail Starred, Answered and Received date, the Files filter (one-filter-panel)

## 7. Store release

- [ ] Privacy policy on pimalaya.org (Google verification asks for it too)
- [ ] Play: fixed-price listing, Data safety form, screenshots light and dark
- [ ] F-Droid: fastlane metadata, the recipe building Rust and the NDK, reproducible to keep the upstream signature
- [ ] GitHub release: signed APKs per ABI and universal, CHANGELOG section cut for the version
- [ ] Release checklist run on a clean phone: setup on Google, Microsoft, Fastmail and an IMAP and DAV host, a day of background sync, offline use
