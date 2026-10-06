---
cairn: change
id: provider-accounts
status: landed
---

# Tasks

## Discovery

- [x] Parse io-pim-discovery 0.9's config JSON: camelCase OAuth fields, `provider` beside `source`.
- [x] Keep SMTP and Graph calendars in the merge.
- [x] Run the provider rules first in the sweep.
- [x] Serve the calendar domain from `msgraphCalendar`, behind the `msgraph://` marker.

## Sign-in

- [x] Run Google and Microsoft grants with the shipped clients, without a resource.
- [x] Pool grants by authorization server and the API their scopes address.
- [x] Merge the submission scope into the mail grant.
- [x] Write the user into the IMAP and SMTP URLs of a mail connection signed in through a browser grant.
- [x] Drop the hand-written Google CardDAV sign-in when the provider rule names it.

## Protocols

- [x] IMAP: `AUTHENTICATE XOAUTH2` for an OAuth credential.
- [x] SMTP: `AUTH XOAUTH2` for an OAuth credential.
- [x] SMTP: STARTTLS on an `smtp://` endpoint, the transport upgrading the socket in place.
- [x] Offer STARTTLS submission rows.
- [x] CalDAV: retry a first pass refused with 400 as a `PROPFIND` listing.
- [x] Graph calendars: list, enumerate, read, create, update, delete.

## Landing

- [x] `:app:assembleDebug` and `:app:testDebugUnitTest` green, cargo tests green.
- [x] Fold the delta, log, CHANGELOG.
