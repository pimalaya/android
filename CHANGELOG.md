# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added mail, contacts, calendars, tasks and files in one app, merged across accounts.
- Added IMAP and SMTP, JMAP, Microsoft Graph, the Gmail API, CardDAV, CalDAV, the Google People, Calendar and Tasks APIs, and Microsoft To Do.
- Added automatic setup from an email address or a domain.

  Provider rules, PACC, autoconfig, CardDAV, CalDAV and JMAP discovery, over DNS-over-HTTPS.
- Added sign-in by password, API token or OAuth 2.0.

  Google and Microsoft clients ship with the app; custom and self-registered clients work too.
- Added a notice before Google's sign-in page, saying which Google data the app accesses.
- Added signing in again, for an account whose password changed or whose sign-in ended.
- Added an offline-first store, pimdir v1 (storage-v1.0.0, sync-v1.0.0, search-v1.0.0), shown at once and written by every action.
- Added incremental sync on every backend, with a three-way merge keeping both sides' edits.
- Added a conflict page for the contacts, entries and tasks edited on both sides.
- Added background sync, and a sync dock showing each step.
- Added a mail download window, older mail loaded on demand.
- Added two-way sync with the phone's own contacts and calendars.
- Added a guard holding back a mass deletion made on the phone.
- Added one message list across accounts and mailboxes.
- Added a mail search over every field, the body included, with its own query language.
- Added a message reader, attachments opened in another app or saved to a folder.
- Added a rich-text composer and an outbox sending each message once.
- Added Deleted items, restoring what the phone still keeps.
- Added a merged contact view, with duplicates found, merged or linked.
- Added a vCard 4.0 editor keeping the properties it does not show.
- Added vCard import and export.
- Added a calendar by week or as a list, repeating events and time zones included.
- Added a Tasks tab with the Getting Things Done lists, a capture from mail and a weekly review.
- Added a Files tab, the phone's folders beside every attachment.
- Added links between messages, contacts, entries and files, some made automatically.
- Added a filter page per list.
- Added a Settings page: the week's first day, the lock screen's notifications, hiding the app in recent apps.
- Added private lock-screen notifications, showing only how many messages arrived.
- Added the app's own palette, light and dark.
- Added English and French.
- Added a refusal to send a password or token over an unencrypted connection.
- Added keeping the store and the accounts out of cloud backups and device transfers.
- Added keeping the app's data on uninstall, on Android 10 and later.
- Added Android 8.0 (API 26) as the minimum version.
- Added signed APKs, one per ABI and a universal one, and a bundle for Google Play.
