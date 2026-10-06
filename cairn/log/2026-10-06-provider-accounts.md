---
cairn: log
change: provider-accounts
landed: 2026-10-06
---

# Google and Microsoft connect for every domain they serve

The libraries moved under the onboarding. io-pim-discovery 0.9 renamed its OAuth fields to camelCase and moved a provider rule's name out of `source`, so every OAuth config failed to parse and the provider match read as none; SMTP had never been in the merge either, so no account was offered a sending server. Fixing those brought back contacts at Google and Microsoft and nothing else, because the sweep never ran the provider rules: they decided whether the hand-written contacts sign-ins showed, and that was all.

**The provider rules run first.** They are the first mechanism of the sweep now, which is the order io-pim-discovery's own `all` gives them, so the collector keeps their endpoints and merges what the domain publishes into them. A provider config is not probed: its sign-in is the provider's own word. Graph calendars are kept in the merge and serve the calendar domain behind `msgraph://`.

**Mail signs in with a token.** IMAP takes `AUTHENTICATE XOAUTH2` and SMTP `AUTH XOAUTH2` when the credential is a token. XOAUTH2 wants a user beside the token, so the connection flow writes the address into the stored URLs' userinfo (`imaps://alice%40example.com@host:993`, RFC 5092 section 3.2) and the credential stays the bare token every other protocol sends; a sender set later from the account settings gets the same. An IMAP refusal of a token answers status 401, so the mail connection renews it once, as the HTTP backends always have.

**SMTP speaks STARTTLS.** `Transport.starttls` wraps the open socket in TLS in place, refusing when bytes arrived past the server's agreement (RFC 3207 section 6), and the SMTP session greets, upgrades, greets again, then authenticates. Submission rows list STARTTLS endpoints after the implicit-TLS ones, and a typed port 587 builds `smtp://`.

**Microsoft calendars run over Graph.** `client/graph_calendar.rs` lists calendars, enumerates lone events and series masters in full every pass at their `changeKey`, reads a series with the exceptions of its own range, and writes through io-msgraph's iCalendar projection. Graph has no conditional write, so the revision is checked right before one and a moved event answers 412, which the push already treats as a rejection. Graph names created events itself, so `createEvent` now answers `{id, etag}` on every backend and the push files the entry under what came back.

**Google CalDAV enumerates.** A first `sync-collection` Google answers with 400 is retried as a `PROPFIND` listing. Google CardDAV, which shares the code, takes the same path.

**Provider grants use the shipped clients.** `startGoogleOauth` and `startMicrosoftOauth` were no longer called, so every browser grant asked the user for an OAuth client; a grant against either authorization endpoint runs with the app's registration again, sends no resource, and is pooled with another domain only when their scopes address the same API (`OnboardingFlow.audienceOf`), since Entra issues a token for one. The mail grant carries the submission server's scope. The hand-written Google CardDAV sign-in is dropped when the provider rule already names it.

Capabilities moved: onboarding (provider rules, provider grants, STARTTLS rows), mail (token sign-in, STARTTLS), calendar (Graph, the 400 fallback).
