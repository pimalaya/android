---
cairn: tasks
change: oauth-verification
---

# Tasks

## 1. Google, before submitting

- [x] Decide the calendar scope (proposal, Decisions): `calendar.events` + `calendar.calendarlist.readonly` (owner, 2026-11-07, after scope-audit.md), in `GOOGLE_SCOPES` since narrow-google-calendar-scope; MGC, `tasks` and `contacts` kept; contacts decided 2026-11-07, `contacts` alone (drop-google-dav)
- [x] Two GCP projects (2026-11-06): "Pimalaya" for production, the one verified, holding the release clients alone; "Pimalaya Debug" (991810147220, the former one) for the debug client, never verified, set to In production so its grants skip the 7-day expiry. The debug client stays out of the demo video and scope experiments stay out of the verified project
- [x] pimalaya.org app page at https://pimalaya.org/android/ and its privacy page at https://pimalaya.org/android/privacy/, the latter rendered from a copy of PRIVACY.md (websites www-android-pages, published 2026-11-06). After a PRIVACY.md edit: `npm run sync:privacy` in the websites repo, then the Publish workflow of pimalaya/_www
- [x] The brand's privacy policy at https://pimalaya.org/privacy/ and terms of use at https://pimalaya.org/terms/, covering every interface, linked from every page's footer (websites www-privacy-terms, published 2026-11-07). Each interface joining the project gets its section or link in the privacy policy first
- [x] (owner, 2026-11-07) Search Console: pimalaya.org as a Domain property (DNS TXT), by an account that is Owner or Editor of the "Pimalaya" project (the verification follows the account, not the project)
- [x] Production Android client in "Pimalaya" (`803561575535-v7nav2ev8dvv995g3j3mhnoq5423abco`), package `org.pimalaya` (a package and SHA-1 pair cannot sit in two projects, so the two clients carry different fingerprints; the Play app signing key's belongs on the production one, the one ownership verification checks). Release builds carry it, debug builds the "Pimalaya Debug" client (google-client-per-build, 2026-11-07). One client serves every release channel: the browser flow never presents the signing key, and each extra client costs a video segment and a build flavour; per-key clients only if the reviewer asks
- [x] The custom URI scheme enabled on the production client (Advanced settings), then a release build's Google sign-in tried through it (owner, 2026-11-07: the CI release build signed in google@pimalaya.org and another Gmail account, both syncing). The "Pimalaya Debug" client stays while testers' accounts hold grants from it (deleting a client fails its refresh tokens; restorable 30 days)
- [ ] Optional: the Play client's ownership verified through the Play Console (Admin on the app)
- [ ] In-app disclosure in each interface before its Google sign-in (proposal, Google facts): the data accessed and how it is used, then a confirm step (a button on Android, a prompt in the command-line wizards), before the browser opens
- [ ] Clients of other interfaces (command-line, terminal, desktop) added to "Pimalaya" only once each is ready for production; each needs its section or link in the privacy policy, its disclosure, and its part in the demo video
- [x] (owner, 2026-11-07) Branding page filled (submission.md §1), 120x120 logo
- [x] Data Access page (owner, 2026-11-07): `https://mail.google.com/`, `calendar`, `tasks` and `contacts`, the scopes release builds ask for since drop-google-dav; the Gmail, Calendar, Tasks and People APIs enabled, the CardDAV and CalDAV APIs left off. `calendar` replaced by `calendar.events` and `calendar.calendarlist.readonly` (owner, 2026-11-07, narrow-google-calendar-scope)
- [ ] Audience: publishing status to In production (verification needs it; ends the 7-day expiry; starts the lifetime 100-user count, closed testers included)
- [ ] Demo video recorded and uploaded unlisted (submission.md §3)

## 2. Google, submitting

- [ ] Brand verification passed (2 to 3 business days)
- [ ] Data access verification submitted: scope justifications and the no-server statement (submission.md §2)
- [ ] Reviewer questions answered by mail; any CASA demand answered with the developer page's server condition before paying for one
- [ ] Verified: the cap lifted, logged here, providers.md updated
- [ ] Yearly recertification date written down (12 months from the approval)

## 3. Microsoft

- [ ] Confirm the shipped client `ba9b19e1-973e-4d7c-aed9-848bd2fee385` lives in the pimalaya.org tenant (Entra admin center > App registrations > Overview, Directory ID); if it does, it is the production client and production-release §4's "production Entra client" is done
- [ ] Authentication: `pimalaya://oauth2redirect` under Mobile and desktop applications, public client flows allowed, any organisational directory and personal Microsoft accounts
- [ ] Branding & properties: name, logo, home page, privacy statement and terms URLs on pimalaya.org, publisher domain pimalaya.org (submission.md §4)
- [ ] API permissions listing the delegated scopes the app asks for, so a tenant admin can grant them in one consent
- [ ] Docs: the admin consent link for tenants that block user consent
- [ ] Publisher verification, when a work tenant's refusal shows up or a legal entity exists: Partner Center enrollment, business verification, a pimalaya.org business mailbox, then the Partner One ID on the registration
