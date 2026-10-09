# Production review (2026-10-09)

Two read-only reviews run before the first public release: what is missing or risky for production, and where the code branches on the backend. Findings cite file:line as of that date. Tick items here or move them into cairn changes as they are taken up.

## 1. Production readiness

### Blockers

- [x] **TLS checked no hostname.** `SSLSocketFactory.createSocket(...)` then `startHandshake()` validates the chain but not the name on Android, so any trusted certificate for any domain passed (HTTPS, IMAPS, SMTPS, both STARTTLS upgrades). Fixed the same day: `Transport.tls` checks the session with the platform's HTTPS verifier (cairn/log/2026-10-09-tls-hostname-check.md).
- [x] **CI cannot resolve io-pimdir.** rust/Cargo.toml:49 patches io-pimdir to `../../io-pimdir`. Push the io-pimdir commits, drop the patch. Fixed 2026-10-09: io-pimdir 0.7.0 from crates.io, patch dropped.
- [x] **Release workflow uses the old names.** .github/workflows/releases.yml exports `CARDAMUM_KEYSTORE*` while android/app/build.gradle.kts reads `PIMALAYA_KEYSTORE*`: CI ships unsigned APKs. Artifacts are still `cardamum*.apk`. Check the keystore alias is `pimalaya`. CI runs no unit tests (audit.yml runs `cargo deny` only). Fixed 2026-10-09: releases.yml maps the `CARDAMUM_KEYSTORE*` secrets onto `PIMALAYA_KEYSTORE*`, the alias `cardamum` through `PIMALAYA_KEYSTORE_ALIAS`, and publishes `pimalaya*.apk`; tests.yml runs clippy, `cargo test` and `:app:testDebugUnitTest`.
- [ ] **Google sign-in on release builds.** The redirect scheme in AndroidManifest.xml:40 is the client registered for the debug key's SHA-1. Each signing key needs its own Android client: GitHub APK key, Play App Signing key, F-Droid key unless the build is reproducible. A new client id changes the manifest's scheme.
- [ ] **Google OAuth verification.** `https://mail.google.com/` (Oauth.java:86) is restricted. On-device only, so no CASA assessment, but the free restricted-scope verification is required (privacy policy on pimalaya.org, demo video, Search Console, yearly re-verification). Unverified: 100 users. In Testing mode refresh tokens expire after 7 days.
- [x] **PRIVACY.md is out of date.** It says bodies and attachments are never downloaded (PRIVACY.md:14) and that removing an account deletes its contacts (PRIVACY.md:44, while they move to the on-device book). Missing: deleted-items retention, full calendar objects, the Google API Services User Data Policy / Limited Use statement. Play's data safety form must match. Fixed 2026-10-09: rewritten to the app as built (the data safety form is still to fill).
- [ ] **The agenda ignores time zones.** Stamps are civil wall-clock (rust/src/calendar.rs:1-9); `CalendarList.stampOf` (CalendarList.java:423-438) drops `Z` and TZID, so UTC events from Graph and Google show hours off. New events are floating, no TZID (calendar.rs:235-245).
- [ ] **Editing one occurrence edits the whole series.** No `RECURRENCE-ID` override is written (cairn/spec/calendar.md:21). At least warn or refuse.

### Should fix before release

- [ ] **Attachments** cannot be opened or saved (MessageView.java:833-860 badges have no handler); compose is plain text, forward drops attachments.
- [ ] **JMAP cannot send or write calendars** (MessageCompose.java:97-104, rust/src/ffi/calendar.rs:35,320), while the standard setup now prefers JMAP. Wire `EmailSubmission` and `CalendarEvent/set`, or rank by declared capability.
- [ ] **No drafts** (MessageCompose.java:163-169 only asks before discarding).
- [ ] **Mail restore is IMAP only** (DeletedItemsPage.java:162).
- [ ] **Device-to-device transfer.** `allowBackup="false"` does not stop D2D on API 31+, and there are no `dataExtractionRules`: encrypted prefs arrive without the Keystore key and `SecureStore.load` throws (SecureStore.java:73-74). Exclude everything, and turn a failed decrypt into a "re-add your accounts" state.
- [ ] **No re-sign-in.** After the one refresh retry (SyncRunner.java:177,365) the user gets a raw `sync_failed`; account settings edit SMTP only (AccountSettings.java:445-470). The only way out is deleting the account, losing the outbox and staged edits.
- [x] **Debug logs in release.** No `-assumenosideeffects` for `Log.d/v`: OAuth authorize URLs with state and PKCE challenge (OauthFlow.java:509), client ids and scopes (:421), the redirect URL (:196), addresses (AccountSettings.java:341, SyncService.java:60), raw engine replies (OfflineEngine.java:217,268; MailEngine.java:252,552). Fixed 2026-10-09: R8 strips `Log.d` and `Log.v` in release (proguard-rules.pro); `Log.w` and `Log.e` stay, some naming an address.
- [x] **adb hooks live in release.** `syncRemote`/`syncLocal` extras on the exported launcher (MainActivity.java:345-348): gate on `BuildConfig.DEBUG`. Fixed 2026-10-09: `syncRemote` is read only when `BuildConfig.DEBUG`; `syncLocal` was already gone.
- [x] **Stale texts.** CHANGELOG.md:37 still announces Play pay-what-you-want tiers; the `WRITE_SYNC_SETTINGS` comment (AndroidManifest.xml:12-15) mentions a background sync choice. Fixed 2026-10-09: the CHANGELOG line is gone; the manifest comment was already rewritten by phone-contacts-mirror.
- [ ] **Open release items.** google-sync live checks, device measure and fold (cairn/changes/google-sync/tasks.md); local-first-actions live checks.
- [ ] **No new-mail notifications, no event reminders.** Decided; say so in the store listing and README.
- [ ] **Versioning.** `versionCode = 7`, `versionName = "0.1.0"` hand-set (build.gradle.kts:26-27); ABI splits need distinct codes for Play and F-Droid.

### Nice to have

- [ ] F-Droid packaging: no fastlane metadata, no gradle wrapper, `cargo ndk` in `preBuild` needs Rust and the NDK in the recipe; reproducible build to keep the upstream signature.
- [ ] Microsoft Entra publisher verification, for tenants that only consent to verified apps.
- [ ] A null JNI reply (`LogErrorAndDefault`, rust/src/ffi.rs) reaches `PimalayaClient.object(null)` (PimalayaClient.java:1307) as a non-`PimalayaException`: map it to an error.
- [ ] A "load images" choice in the HTML reader (MessageView.java:739-740).
- [ ] STARTTLS comment in AccountSettings.java:443 contradicts spec mail.md:296.
- [ ] Remote search; local search covers sender and subject only.
- [ ] StrictMode in debug, a local crash log the user can share.

### Checked and fine

R8 keeps every native-called member (client/consumer-rules.pro vs rust/src/client.rs:166-211, offline.rs:278); targetSdk 35 and 16 KB alignment; the WebView is sandboxed; store migrations are incremental; no proprietary dependencies.

## 2. Backend-conditional code

io-pimdir already offers the seam: a source declares capabilities (STORAGE §15.6, `PimdirStore::declare`, io-pimdir/src/client.rs:521, vocabulary in src/capability.rs: `mail.message.add`, `mail.submit`, `mail.submit.copy`, `mail.flags.answered`, `mail.message.remove`, `calendar.item.*`). Nothing in this repository declares or reads them.

### Where the app branches on the backend

- **Source of the leak:** `PimalayaClient.isCarddav/isGraph/isGoogle/isJmap` (PimalayaClient.java:334-351), read from `accountInfo().backend`. `isGoogle` covers Gmail, People and Calendar despite its doc. `isAccountLevel` and `expungesOne` (PimalayaClient.java:668) are already traits: the right pattern.
- **Mail:** account-wide listing (MailEngine.java:125); never appends, provider files the sent copy (MailEngine.java:787-792, duplicated in DeletedItemsPage.java:226-233); no answered marker (MessageCompose.java:262-268); JMAP sends nothing (MessageCompose.java:337, mirrored in rust/src/ffi/mail.rs:814); JMAP without trash (MessageView.java:533-537); no submission server (AccountSettings.java:255, OnboardingFlow.java:356, ffi/mail.rs:819); pool size per backend (MailPool.java:78-88).
- **Contacts:** Graph priming (OfflineEngine.java:441), fetch strategy (:538-563), push strategy (:727-737), a private `isJmap()` re-derived (:1277-1289); `CardStore.isCarddavUrl` (CardStore.java:492-503) duplicates Rust `account::addressing_key` (account.rs:162).
- **Rust:** eleven `if session.is_x()` chains in rust/src/ffi/mail.rs (185, 234-262, 329-336, 350-372, 402-425, 596-611, 623-641, 653-669, 680-693, 706, 814-830); `JMAP_UNSUPPORTED` calendar writes (ffi/calendar.rs:169,319,501,533); `Backend::of` reports IMAP and local accounts as `carddav` (account.rs:57-67).
- **Fine where it is:** onboarding and OAuth provider logic, the transport's scheme switch, client/dispatch.rs, role switches.

### Proposals, by payoff over effort

1. **Declare pimdir capabilities from the Rust session** (high, medium): built per `MailKind` in `MailSession::open` (session.rs:113), returned beside `expungesOne`, declared once per open; replaces MailEngine:787, DeletedItemsPage:230, MessageCompose:265 and :337, MessageView:536, the `expungesOne` reads; pimdir then refuses impossible mutations at stage time. Same for calendar and contacts.
2. **Account traits in `accountInfo`** (high, low): `listsAccountWide`, `submitsOverSession`, `maxSessions`; then delete `PimalayaClient.isGraph/isGoogle/isJmap`.
3. **Drop `CardStore.isCarddavUrl`** for the key Rust computes (low).
4. **Contact fetch and push strategy into Rust** (high, high): `fetchCards`/`pushCards` in dispatch.rs; CardDAV concurrency stays a trait.
5. **One `MailAdapter` trait** implemented per backend, dispatched once, replacing the `is_*` chains (medium, medium).
6. **Fix `Backend::of`** for IMAP and local (low).

### Gmail "All mail" inside the adapter

- Roster (gmail.rs:343): a synthetic mailbox, id no label can take, role `all`.
- Membership (`belongs`, gmail.rs:136): every message outside spam and trash; deltas follow unchanged.
- Listing (gmail_sync.rs:529, gmail.rs:381): always the account listing the run already shares.
- Relocation (gmail.rs:156): All to X adds X, X to All removes X (archive), copy into All is a no-op.
- App side, generic only: a chip or drawer row for role `all`, and **dedup in pimdir**: list_mail_page_filtered.sql returns one row per placement, so a message under two labels (or in two Fastmail mailboxes) already lists twice. One row per `seq`, counts and search by distinct `seq`. To decide: whether dedup crosses accounts, and that a flag staged on one placement applies to its siblings.
