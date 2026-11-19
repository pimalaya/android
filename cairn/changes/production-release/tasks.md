---
cairn: tasks
change: production-release
---

# Tasks

## 0. Started now: what waits on others

- [x] Privacy policy on pimalaya.org, matching PRIVACY.md (Google's verification and Play's Data safety form both ask for it): https://pimalaya.org/android/privacy/, rendered from PRIVACY.md (oauth-verification §1)
- [ ] Google: restricted-scope verification for `https://mail.google.com/` submitted (privacy policy, demo video, Search Console for pimalaya.org, yearly renewal); until then 100 users and 7-day refresh tokens in Testing (oauth-verification); the owner runs it in another session (2026-11-07)
- [x] Play developer account: personal, created long before 2023-11-13 (owner, 2026-11-07), so the closed test of 12 testers for 14 days asked of newer personal accounts does not apply (Play Console's dashboard lists it when it does); the app is created and on internal testing

## 1. Data loss and correctness

Restated against the code on 2026-11-06. Gone since the 2026-10-09 review: the IMAP body filed under another mailbox's message (437be93: bodies are shared by link id only on Gmail and JMAP, `MailEngine.lookup`), the Gmail IMAP first step listing a whole account (437be93, 3d999f8: each ticked mailbox from its own floor), the sends repeated on an ambiguous failure (write-once-and-oauth-dos: *May have been sent*, `Message-ID` checks before an IMAP COPY fallback or APPEND, Graph `transactionId`, no resend after a timeout), Gmail trash and junk unlisted on a fresh device (dropped from the spec on 2026-10-12, mail-filter-scope).

- [x] A session closed under a running verb: `WorkerPool` returns when its wait is interrupted (sync/WorkerPool.java:201-207) and `MainActivity.onDestroy` interrupts it (`shutdownNow`), so a rotation during a mail pass frees a native session a worker still uses. The wait uninterruptible, the interrupt restored after; `MailSession` verbs, `close` and `reopen` synchronized (audit-s1-residuals I6, whose MailPool is gone)
- [x] Protection, not a feature: the phone pass deleted upstream whatever another app deleted from a mirrored book or calendar (a cleanup tool, a bulk selection, a glitch), and erased the phone row before the store recorded it. Built 2026-11-06 (5cf0f15, phone-deletions-held): more than half of at least 20 held, a dialog, rows erased after the round. Checked on the device by the owner and archived (cairn/log/2026-11-06-phone-deletions-held.md); the aggregation half stays in audit-s1-residuals I7
- [ ] A fake ContactsContract provider, so the contacts spoke of the phone pass is tested as the calendar one is (FakeCalendarProvider)
- [x] Gmail: a message sent to oneself arrives read: the staged Sent copy carries `\Seen` (mail/MailSubmit.java:121) and Gmail's flags are per message, so the push marks the inbox copy read. Fixed: the staged copy takes the markers of the provider's own before landing (cairn/log/2026-11-06-sent-copy-takes-provider-markers.md)
- [x] IMAP: a staged copy's origin was found again by link id across every collection of the source, so a copy could take another mailbox's message under the same UID; the statement now follows pimdir's `origin_for_link` within the account and the `Message-ID` (cairn/log/2026-11-06-a-copy-from-its-own-mailbox.md)
- [ ] IMAP: an IMAP link id was a bare UID, which every mailbox reuses, so the inbox's message 42 carrying invoice.pdf and the archive's message 42 carrying beach.jpg shared one attachment record (`part:42#2`). Built 2026-11-06 (6b604e4, imap-link-id-per-account): the link id is `<UID>:<UIDVALIDITY>:<mailbox>`. The root cause is the app naming a message by its server id where pimdir names it by its `Message-ID` (STORAGE §9), the server id being the handle; the owner chose on 2026-11-07 to follow pimdir on every backend: built the same day (mail-named-by-message-id), that encoding removed; owed, the device check with IMAP, Gmail, Graph and Fastmail accounts added again, and a UIDVALIDITY change answered by pimdir's rekey (follow-up)
- [ ] Repeated writes left after M6: done, a send the app stopped in parked uncertain (cairn/log/2026-11-06-a-send-the-app-stopped-in-waits.md) and a copy skipped when its target holds the message (cairn/log/2026-11-06-a-copy-is-not-made-twice.md); left, creates rerun after a lost answer: a contact created on the phone reaches Google, the answer is lost, the next sync creates it again and the server holds two. Creates without an idempotency key (Graph contacts, Google People, Google Tasks, Microsoft To Do, JMAP cards and events). Checked 2026-11-06: the next pass lists the server's copy named by its identity and pimdir lands the pending create on it (CalendarMirrorTest `aCreateWhoseAnswerWasLostIsFiledOnce`), so every backend that keeps the UID files once: CalDAV, CardDAV, JMAP, Google Calendar (`iCalUID`), Google People (`clientData`), Graph contacts and To Do (the stash). The JMAP lookup by `uid` (c0a7767) was reverted as redundant, a request per create for nothing; the Google Calendar 409 read-back stays, costing nothing unless Google answers 409. Google Tasks, whose UID is Google's task id, so a lost create filed a second task: built 2026-11-07 (google-tasks-lost-create), an unbound arrival reading the same as a pending create named after it, live-checked and landed the same day; its other limits said to users rather than worked around (owner, 2026-11-07: google-tasks-limits-said) (network-trust-hardening, whose Open M6 and L1 are stale)
- [x] Gmail: trash or junk ticked at the first step landed empty as a closed round (mail/GmailStep.java:84) and no later step fills it; the first step leaves them to a round of their own (cairn/log/2026-11-06-gmail-trash-opens-by-a-round.md)
- [x] IMAP without SORT: the floor sample stopped early on imports appended in rising date order and listed every envelope back to their dates; now checked by the round's search and a halving by day (cairn/log/2026-11-06-the-floor-checked-without-sort.md). mail.md still says 50 while the code follows fill-from-two-queues' 25 (its fold owed)
- [x] A null byte array from Java turned into an empty one at the bridge (`unwrap_or_default` in rust/src/ffi/offline.rs and ffi/mail.rs): unreachable today, an empty SMTP send if it ever was; now an error reply, and `PimdirSql.hash` throws on it. `read_string` still reads a null string as empty
- [x] A send Gmail refused for a rate or a quota (a 403) was parked as refused for good; the throttle now marks the exchange `limited` and the row stays pending (cairn/log/2026-11-06-a-send-refused-for-a-rate-stays-queued.md)
- [x] A Google Calendar insert rerun under the same iCalUID answered 409 on every pass; checked live, now read back by `iCalUID` and bound (cairn/log/2026-11-06-a-meeting-inserted-twice.md)

## 2. Device checks, then fold and archive (owner)

- [ ] Device check of the changes waiting on it alone, on one release-candidate build against one checklist gathered from their tasks: account-settings-page, attachments-as-files, attendee-notifications, automatic-references, background-check, calendar-conflict-form, calendar-occurrence-writes, calendar-zones-and-series, files-review-fixes, files-tab, filter-page-account-hides, gmail-first-sync-stall, item-links, jmap-calendar-writes, jmap-release-readiness, mail-download-window, mail-filter-apply, mail-filter-scope, mail-sync-robustness, new-task-page-and-drop-in, phone-contacts-mirror, redesign-for-the-palette, sync-dock, sync-feedback-pinned, sync-strip-progress, task-batch-actions, tasks-in-google-and-microsoft, write-once-and-oauth-dos
- [ ] Device check of the changes with work left besides: fill-from-two-queues, mail-load-more, mail-load-more-graph, mail-load-more-imap, mail-load-more-jmap, one-onboarding-path, phone-calendar-mirror, sync-strip-in-app-bar (assembleDebug never run), crash-and-surface-hardening (OAuth loopback listener)
- [ ] Live checks on the Google test account (google-sync: history labelIds, batch host, iCalUID listing, People token and deletions, the device measure)
- [ ] Each checked change folded into the spec, logged and archived
- [x] Device-checked by the owner and on the device on 2026-11-19 (master d643ecd, then 880cbb6): guarded-contact-updates on JMAP and Graph (both edits kept), a-message-is-filed-somewhere (every Gmail message in its labels and All Mail), the ical-rs 0.7.2 `UNTIL` of a JMAP series, the store opening after the A7 sync check, hide-in-recents, sign-in-again reopening after Back (PR #2) and keeping its position (PR #3); their folds owed with the rest
- [ ] Archive the changes already done: body-loss-races, body-run-loop, drop-sync-mark, google-delete-loss, mailbox-refusal-skipped, oauth-pending-grant-encrypted, store-performance (fold and log first if missing), gtd-hardening (landed), ical-rs-0-6 (fold)
- [ ] Archive bundled-sqlite as superseded: platform-sqlite (landed 2026-11-01) put the store on the platform's SQLite and dropped requery

## 3. Released libraries and supply chain

- [x] io-http 0.6.0, ical-rs 0.7.0, io-imap 0.8.1, io-webdav 0.7.0, io-jmap 0.7.0, io-pim-discovery 0.11.0 released and pinned from crates.io (checked 2026-11-06)
- [x] No git source left in rust/Cargo.toml: io-pimdir 0.18.0 (draft-14), io-msgraph, io-gtasks and every other library pinned from crates.io; `cargo deny` refuses any git source (2026-11-19, release-pinned-libs archived)
- [-] A per-call body bound: not needed, io-http 0.8 refuses every buffered answer past 256 MiB by default (owner, 2026-11-19; release-pinned-libs, spec/network-trust.md)
- [x] CI builds unsigned, then signs in a job of its own (the keystore never reaches the build) on every master and tag run, unsigned and signed artifacts side by side, the GitHub release on tags only (40cbe85, then 2026-11-19; release-supply-chain). The keystore secrets are repository secrets; the `release` environment is created by its first run, unprotected by the owner's choice
- [x] Actions pinned by SHA, least `permissions` (`contents: write` on the tag-only publish job alone), the Cachix push token in the release build alone, the other jobs pulling with none (40cbe85, then 2026-11-19)
- [x] Gradle verification metadata (40cbe85, completed for cold and restored CI caches in 170fec0 and d643ecd); the Gradle wrapper properties go with the F-Droid recipe (below)
- [ ] `cargo deny` with yanked denied, no git sources, a weekly audit run: built 2026-11-19, green (RUSTSEC-2026-0543 ignored, chrono imposed by imap-types, its switch upstream in imap-codec issue 752); owed, a release dry run on a fork
- [ ] Versioning: versionCode and versionName from the tag; distinct codes per ABI for the GitHub APKs and F-Droid, Play taking the one bundle .github/workflows/releases.yml already builds (android/app/build.gradle.kts)

## 4. Sign-in for real users

- [x] Google: release builds on the "Pimalaya" project's client, debug builds on "Pimalaya Debug"'s, the redirect scheme following ([log](../../log/2026-11-07-google-client-per-build.md)). One client serves every release channel; per-signing-key clients only if Google's reviewer asks (oauth-verification)
- [ ] Microsoft: the production Entra client in place of the testing one; publisher verification for tenants that require it (oauth-verification)
- [ ] Re-sign-in: an account whose grant or password stopped working signs in again, keeping its outbox and staged edits. Built in sign-in-again (2026-11-07); its reopening after Back (PR #2, b0303eb) and the settings position kept on return (PR #3, 880cbb6) checked on the device on 2026-11-19; owed, a personal Microsoft account read by its identity check
- [x] Fastmail: one consent: a Fastmail account set up today runs mail, contacts and calendars on its one JMAP session (Fastmail serves `urn:ietf:params:jmap:calendars`), one grant and one consent, observed by the owner on 2026-11-19; the CalDAV shard resource fix stays owed for an account that picks CalDAV (one-fastmail-consent)

## 5. Security residuals

- [x] Lock-screen notifications private, a public version saying only the count, a Settings switch showing sender and subject (audit-s1-residuals I1; [log](../../log/2026-11-07-locked-notifications-show-the-count.md))
- [ ] Sync adapters not parallel, no URL in warnings (audit-s1-residuals L1, L3)
- [ ] OAuth: issuer pairing (H3), `iss` required where advertised (M1), https-only endpoints in io-oauth (M2), RFC 8707 `resource` (L3), identity check after a grant (L4), error redirects before the state check (L5): one plan, oauth-hardening (2026-11-07)
- [-] Network warnings (old TLS L6, discovery redirects L2, the public-suffix rule, L8 documented): postponed by the owner on 2026-11-19 (network-warnings)

## 6. What a user meets first

- [ ] Drafts: a message being written is kept and reopened (later, by the owner's choice of 2026-11-07)
- [ ] Attachments added in the composer, and kept on a forward (later, by the owner's choice of 2026-11-07)
- [ ] Restoring a deleted message on every backend, not IMAP alone (DeletedItemsPage)
- [ ] Gmail: a message under a label the store does not hold yet has its body read twice, the next pass's round over the new label filing it; not a loss. The label is now logged and counted rather than dropped, and the dead Graph and JMAP step paths are deleted (a-message-is-filed-somewhere, eeede3a, 2026-11-19); the double read remains
- [ ] Store performance follow-ups: a query's roster, refcounts by touched bodies, owed keys in a table, the 2 GiB guard and the memory-mapped message read landed 2026-11-19 (a8b329b), contacts rows already from summaries; left, the agenda's no-index fallback (an io-pimdir statement) and a part streamed rather than decoded whole (store-performance-followups)
- [ ] The filter panel's leftovers: mail Starred, Answered and Received date, the Files filter (one-filter-panel)

## 7. Store release

- [ ] One release once everything is ready (owner, 2026-11-07): the store at storage-v1.0.0 (pimdir's owner fence decided, io-pimdir released at v1, the app following it) and the io-* libraries released at the final validation; Play internal testing until then. As of 2026-11-19 the app is on storage-draft-14 (io-pimdir 0.18.0)
- [x] Play store listing: name, short description (80 characters), full description, 512x512 icon, 1024x500 feature graphic, category, contact email, privacy policy URL. Phone screenshots rendered from the design canvas on 2026-11-07, light and dark, 1080x1920 with a caption (Play refuses a side over twice the other, so the canvas's 390x844 frames sit on the wash); the README's full-height ones in screenshots/; filled (owner, 2026-11-08)
- [x] Play pricing: the fixed price and a payments profile. Set at 5.99 (owner, 2026-11-08). Play never turns a free app paid, so App pricing is checked still open before any wider track
- [x] Play EU trader declaration (it shows an address publicly), done (owner, 2026-11-08)
- [x] Play App content: Data safety form (PRIVACY.md), App access (sign-in steps and a working test account for the reviewer, since the app shows nothing before an account), content rating questionnaire, target audience, ads (none), Advertising ID (unused). No sensitive-permission or foreground-service declaration: the manifest asks for neither; filled (owner, 2026-11-08)
- [ ] Play production only once Google's verification lifts the 100-user cap (oauth-verification §2), else Google sign-in stops at 100 users, paying ones included
- [ ] F-Droid: fastlane metadata, the recipe building Rust and the NDK, reproducible to keep the upstream signature
- [ ] GitHub release: signed APKs per ABI and universal, CHANGELOG section cut for the version
- [ ] Release checklist run on a clean phone: setup on Google, Microsoft, Fastmail and an IMAP and DAV host, a day of background sync, offline use
