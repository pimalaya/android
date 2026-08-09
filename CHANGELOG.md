# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added multi-account contact management over CardDAV, JMAP for Contacts, Microsoft Graph and the Google People API, unified behind one client and a contact-first view that merges every account's addressbooks.
- Added mail and calendars beside the contacts, read-only and merged across accounts the same way: mailboxes over IMAP or JMAP in one message list, calendars over CalDAV or JMAP in one agenda. A JMAP account serves all three domains from its single session. A message row carries its subject, sender, mailbox and account, an avatar keyed by the sender's address, a paperclip leading it when it carries an attachment, and its replied and important marks; an agenda row carries the same shape, with the glyph of its component leading it, its calendar's initial in its calendar's own colour, and how far off it is where a message puts its date.
- Added a reader behind every message: a header card with the sender's avatar, the subject, the full date and how long ago it was, and one badge per attachment, over the message itself. HTML bodies render sandboxed, with scripting off, network loads blocked and images not loaded, so opening a message cannot report back to whoever sent it.
- Added a page behind every agenda row: the page a contact opens on, read and edited in the same place, with each row a dialog, an add button for the properties the entry has not got yet, and a save. The sections follow the component, since an event runs between two moments, a to-do is due and partly done, and a journal entry is written on a day. An edit patches the object through its syntax tree, so alarms, attendees and everything else the page does not manage survive untouched, and pushes it back guarded by the entry's ETag. CalDAV only for now; a JMAP calendar says so rather than pretending to save.
- Added dates worded the way a reader would tell them, one vocabulary across the three domains: Today, Yesterday and 3 days ago in the message list, in 20 minutes and 2 hours ago in the agenda, the exact date with the distance beside it in an open message. Told in calendar days rather than elapsed hours, so a message from 23:00 last night reads Yesterday.
- Fixed: an account signed in with OAuth stopped syncing until it was added again. Two faults, both on the token refresh. A JMAP server's first call reported its 401 in a shape the app did not recognise as one, so the refresh was never attempted; and each domain kept its own copy of the sign-in, so with a provider that issues a new refresh token every time (Fastmail among them), refreshing contacts retired the copies mail and calendars were holding. A sign-in is now stored once and named by every domain it covers, so renewing it renews all of them and there is no second copy to fall behind. Accounts stored by an earlier version are dropped on upgrade and have to be added again, once.
- Fixed: a message whose attachment carried no `Content-Disposition` showed no paperclip. A part naming a file counts as an attachment now, which is how plenty of mail in the wild is written; a part the sender marked inline still does not.
- Added one app bar across the three domains: the burger opens the accounts drawer, one button each switches to mail, contacts or calendars with the one being shown in the accent, and that domain's actions follow. The app opens on the mail list, and every domain has an add button.
- Added offline-first storage: a full local vCard store rendered instantly, with edits staged and pushed on the next sync.
- Added incremental synchronization on every backend through the io-offline replica engine, with three-way-merge conflicts that auto-resolve clean divergences and surface only genuine same-field collisions for manual resolution.
- Added two-way phone synchronization: each addressbook mirrors into its own Android account (the DAVx5 pattern), so edits from any contacts app converge into the store and ride upstream.
- Added scheduled background synchronization per addressbook (WorkManager), from every fifteen minutes to daily, reporting each pass in a notification.
- Added automatic setup from an email address or a bare domain: parallel provider-rule (MX), PACC, CardDAV and JMAP discovery over a DNS-over-HTTPS resolver, with a standard or advanced onboarding path.
- Added authentication by password, API token or OAuth 2.0, with shipped Google and Microsoft clients, custom clients, and zero-registration OAuth (metadata discovery, dynamic client registration and PKCE); credentials are encrypted in the Android Keystore.
- Added a full vCard 4.0 editor: a form covering every form-worthy property, an advanced per-property editor completing the RFC 6350 registries with structured-value fields, and a raw source editor.
- Added a merged contact view with UID-based deduplication, manual link and unlink, a merge flow, a semi-automatic duplicate remover, search, vCard import and export, and a divergence flag opening a conflict-resolution form.
- Added lossless round-trips for Google People and Microsoft Graph: vCard lines with no native slot are stashed server-side and restored verbatim, and provider-only fields ride the vCard as read-only vendor properties.
- Added a next-birthday peek computed from the merged cards.
- Added a Google Play support prompt with one-time pay-what-you-want tiers; the FOSS builds ship free and ungated.
- Added the packaging: a Nix flake pinning the toolchain and a release workflow assembling one signed APK per ABI plus a universal one.
- Set the minimum supported Android version to 8.0 (API 26).
