---
cairn: change
id: network-trust-hardening
status: active
created: 2026-10-11
---

# Credentials go only where they are meant to, encrypted

## Why

The S2 network-trust audit found the app's credentials could leave in clear or reach the wrong host. The app's own redirect runners re-sent the password or token to any target, https to http included, with no hop limit. Nothing checked a scheme before attaching a credential: a typed `imap://` or `http://` server, a discovered non-TLS DAV SRV record, a plain URL in a PACC document or a server-named absolute href all carried the password in clear. The shipped Google and Microsoft clients were picked by matching the discovered authorization endpoint alone, so a forged autoconfig pairing Google's sign-in with another IMAP host would hand that host a Gmail token. OAuth had no mix-up defence (RFC 9207 `iss`, RFC 8414 issuer equality), allowed http endpoints and opened any scheme in the browser. The user was never shown where a password went. A `mailconf=` TXT record could name an http URL. Sync workers refreshed one rotating refresh token concurrently, signing accounts out at providers detecting reuse.

## What

- One bridge rule, where the bytes leave: `Client::write` refuses an HTTP `Authorization` header over plain http, an IMAP `AUTHENTICATE`/`LOGIN` over imap, an SMTP `AUTH` before STARTTLS (`trust::refuse_cleartext_credential`); the Java `Transport.write` applies the same rule to any non-TLS socket. IMAP sign-in refuses `imap://` up front (`require_tls`).
- Redirects (`trust::Redirects`): at most 5, never from https to anything else, credentialed WebDAV and JMAP session/upload requests refuse another origin, JMAP blob downloads follow another origin without the `Authorization` header (fetch semantics).
- Server-named principal and home-set hrefs stay on the origin, or on an https sibling host under the same site (iCloud's `pNN-caldav.icloud.com`), else refused (`trust::trusted_href`).
- Discovery: DAV SRV resolution is secure-only, `mailconf=` URLs are https only, and the onboarding flow keeps only https HTTP endpoints, `imaps` typed mail servers, `https` typed DAV URLs and `smtp`/`smtps` typed submission servers (`Endpoints`).
- Shipped OAuth clients only when every endpoint of the grant (the domains' bases and the submission server) is on the provider's host allowlist, with its fixed scopes (`Oauth.shipped`, `Oauth.fixedScope`); otherwise the grant is a generic one (the user's own client page).
- OAuth: https required for the issuer, every metadata endpoint, the token and registration endpoints and the authorization URL the browser opens (loopback http aside); the metadata's `issuer` must equal the one asked for; an issuer-flow redirect whose `iss` names another issuer is refused; the redirect validation error no longer carries the redirect URL (the code).
- The sign-in card names each endpoint's scheme and host before a password or token goes out, flagged in the error colour when outside the address's domain (en, fr).
- Token renewal is serialized per credential process-wide (`TokenRenewal`): a worker finding the stored credential already renewed reuses it, and the renewal is written onto the stored account (`SecureStore.renew`), never a stale snapshot.
- `Transport` turns a host `java.net.URI` cannot parse into a `PimalayaException` rather than an NPE.

Out of scope, left open in tasks.md: M6 duplicate writes and the low findings not listed above.
