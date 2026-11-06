---
cairn: change
id: oauth-verification
status: active
created: 2026-11-06
---

# Google and Microsoft production sign-in

## Why

The shipped Google client runs unverified in Testing: 100 test users, refresh tokens dead after 7 days. Real users need Google's restricted-scope verification, which takes weeks outside the code, so it starts before the rest of production-release (its §0 and §4 items point here). Microsoft has no review, but work tenants under the default consent policy only let users consent to apps from verified publishers.

## What

The steps to take both shipped clients to production, in order, with the facts they rest on (read on 2026-11-06 from Google's and Microsoft's own pages) and the texts to paste into the consoles (submission.md).

### Google, what the pages say

- Restricted: `https://mail.google.com/` "includes any usage of IMAP, SMTP, and POP3" (support.google.com/cloud/answer/13464325). The app needs it twice over: IMAP and SMTP, and Gmail API `messages.delete` (rust/src/mail/gmail.rs), which `gmail.modify` cannot do.
- Security assessment (CASA): the developer page limits it to apps that "store or transmit restricted scope data on servers" and to "every app that ... has the ability to access data from or through a third-party server" (developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification). The Security Assessment help page and the Gmail API policy state it for restricted scopes with no such condition. No server-free exemption is written anywhere: the submission states the app has no server and expects the reviewer to rule.
- Limited Use: the statement "cannot be placed only in a privacy policy or terms of service"; it goes in the app or on a website of the app (developers.google.com/gmail/api/policy). PRIVACY.md holds it, so the homepage needs it too.
- Homepage on a verified domain, public, describing the app, not a Play listing, linking the privacy policy; the privacy policy on the same domain, identical to the consent screen's link (support.google.com/cloud/answer/13464321).
- Authorized domains verified in Search Console by an Owner or Editor of the GCP project.
- Demo video: unlisted YouTube, English, the whole flow, the app name on the consent screen, the client ID in the address bar, every requested scope and the feature using it; each OAuth client of the project shown.
- Unverified in production: an unverified-app warning and 100 new users over the project's lifetime, never reset; Testing: 100 test users, grants and refresh tokens expiring 7 days after consent (support.google.com/cloud/answer/15549945).
- Timing: brand review 2 to 3 business days, sensitive up to 10 days, restricted "several weeks"; restricted scopes recertify every 12 months (support.google.com/cloud/answer/13463816).
- Installed apps: no incremental authorization, so the consent screen lists every scope at once (developers.google.com/identity/protocols/oauth2/native-app).
- Android clients: one per package and SHA-1. New Android clients have the custom URI scheme off, enabled under the client's Advanced settings (Google developers blog, 2023-10-02); the native-app page now says custom schemes are "no longer supported on Android", recommending Play Services' `AuthorizationClient`, which F-Droid and de-Googled phones lack. Ownership verification through the Play Console is optional and Play-only (developer.android.com/identity/authorization).
- App name: no Google product name in it ("Gmail for Android" is the refused example).

### Microsoft, what the pages say

- No review step: the registration serves every tenant and personal account as it is.
- Publisher verification (the blue badge) is free, a few business days once the partner account is verified, and needs: a Microsoft AI Cloud Partner Program partner global account (Partner One ID, formerly MPN ID) that passed business verification with formation or registration documents; a business email, not a free one, on the publisher domain or a domain verified on the tenant; MFA; Application Administrator in Entra and an admin role in Partner Center (learn.microsoft.com/entra/identity-platform/publisher-verification-overview). Whether a sole trader qualifies is not written: unconfirmed.
- Publisher domain: not `*.onmicrosoft.com`; a domain verified on the tenant, or `https://pimalaya.org/.well-known/microsoft-identity-association.json` naming the app ID.
- Tenants on "Allow user consent for apps from verified publishers, for selected permissions" let users consent only to verified publishers' apps, for permissions the admin classed low impact; otherwise the admin consents for the tenant.

## Decisions

- No server, ever, in the restricted path: a token broker or a Gmail Pub/Sub relay that touches restricted data would bring CASA in for good.
- The custom URI scheme stays: `AuthorizationClient` needs Play Services. If Google refuses the scheme at review, a fallback is to be found then, not built ahead.
- Open: whether the shipped Google client keeps both contacts scopes (`carddav` and `contacts`). Same data twice; the reviewer may ask why. Dropping `carddav` from `GOOGLE_SCOPES` (android/app/src/main/java/org/pimalaya/account/Oauth.java) leaves Google CardDAV to a user's own client.
- Open: `calendar` or `calendar.events` with `calendar.calendarlist.readonly`. The Calendar API path only lists calendars (`calendarList.list`) and reads and writes events (rust/src/calendar), so the narrower pair covers it; whether Google's CalDAV endpoint, the other calendar option, accepts the narrower pair is untested. Both are sensitive, so this changes the reviewer's questions, not the burden.
