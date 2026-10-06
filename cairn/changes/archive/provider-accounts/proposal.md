---
cairn: change
id: provider-accounts
status: landed
created: 2026-10-06
---

# Connect Google and Microsoft for every domain they serve

## Why

io-pim-discovery 0.9 changed its JSON (camelCase OAuth fields, the provider moved out of `source`), and the onboarding broke on it: every OAuth config failed to parse, SMTP was merged away, and Google and Microsoft addresses were offered contacts at most. Fixing the parsing is not enough, because the protocol sweep never ran the provider rules: those are what name Google's IMAP, SMTP and CalDAV and Microsoft's IMAP, SMTP and Graph, on a custom domain as much as on gmail.com, and they were only used to decide whether to show the hand-written contacts sign-ins. MOA, on the same library, offers every domain because `pim-discovery all` merges them first.

What the rules name, the app could mostly not drive:

- IMAP and SMTP signed in with `AUTHENTICATE PLAIN` alone, and Microsoft accepts only OAuth there.
- SMTP spoke implicit TLS alone, and Microsoft submits on port 587 with STARTTLS only.
- Calendars were CalDAV or JMAP, and Microsoft has neither.
- Google's CalDAV answers 400 to a `sync-collection` with no token, which is how every first pass starts.
- Every browser grant asked the user for an OAuth client: the shipped Google and Microsoft clients were no longer wired to anything.

## What

**Discovery runs the provider rules first.** They become the first mechanism of the sweep, ahead of PACC, so the collector prefers their endpoints and merges the others' authentication methods into them, which is the library's own order.

**IMAP and SMTP sign in with a token.** An OAuth credential (empty login, as on every other protocol) authenticates with SASL `XOAUTH2`, the user named in the endpoint URL's userinfo the way RFC 5092 names it (`imaps://alice%40example.com@host:993`). The connection flow writes it there when a mail connection signs in through a browser grant.

**SMTP speaks STARTTLS.** An `smtp://` endpoint is greeted, sent `EHLO` and `STARTTLS`, upgraded to TLS by the transport on the same socket, then greeted again before authentication. A server answering bytes past its `220` is refused (RFC 3207 section 6). Submission rows offer STARTTLS endpoints beside implicit-TLS ones.

**Microsoft calendars run over Graph.** A calendar connection behind the `msgraph://` marker lists the user's calendars and their events through io-msgraph, projected to and from iCalendar by its `ical` feature: a series is one item read with its exceptions, its `changeKey` the revision. Graph has no event delta outside a time window, so every pass lists the calendar in full. Graph has no conditional write either, so a write checks the revision it was staged against right before it.

**Google CalDAV is enumerated.** A first pass the server answers with 400 is retried as a `PROPFIND` listing, which carries no token, so every pass on that calendar is a complete one.

**Provider grants use the shipped clients.** A grant against Google's or Microsoft's authorization endpoint runs with the app's own registration, sends no RFC 8707 resource (both answer `invalid_target`), and is pooled with another domain only when their scopes address the same API: Entra refuses one token for Outlook and Graph together, so Microsoft mail and calendars are two consents. The mail grant carries its submission server's scope too.

## What this does not do

**Gmail over the Gmail API, Google calendars over Calendar v3, Microsoft mail over Graph.** Discovery names them; IMAP, SMTP and CalDAV reach the same data with readers the app already has.

**Google verification.** Gmail's `https://mail.google.com/` scope is restricted, so until the paid CASA assessment it works for test users only; a Google address can still connect mail with an app password, which the standard setup picks.

**Incremental Graph calendars.** The full listing is correct and costs one request per page; an event delta needs a time window, and an event leaving it would read as deleted.
