---
cairn: delta
change: network-trust-hardening
---

Folds into `spec/network-trust.md` (new capability: where a credential may go) and `spec/onboarding.md` (setup rules, the shipped grant).

## ADDED Requirements

### Requirement: A credential never leaves unencrypted
The app SHALL send a password or token only over TLS: `https`, `imaps`, `smtps`, or `smtp` after a completed STARTTLS. The bridge SHALL refuse to write an HTTP `Authorization` header over plain http, an IMAP `AUTHENTICATE` or `LOGIN` over plain imap and an SMTP `AUTH` before STARTTLS, and the transport SHALL refuse the same on any socket that is not TLS. An `imap://` account SHALL be refused before its greeting is read, the bridge speaking no IMAP STARTTLS.

#### Scenario: A plain server reaches the bridge
- GIVEN an account whose endpoint is `http://dav.example.org/` or `imap://mail.example.org:143`
- WHEN it signs in
- THEN nothing carrying the credential is written, and the failure names the server without the credential

### Requirement: Redirects are bounded and keep the credential home
An HTTP request carrying a credential SHALL follow at most five redirects, SHALL NOT follow one from https to anything else, and SHALL NOT carry its credential to another origin: a WebDAV request or a JMAP session or upload request SHALL fail there, and a JMAP blob download SHALL follow it without its `Authorization` header.

#### Scenario: A download served from a CDN
- GIVEN a JMAP download URL answering 302 to `https://cdn.example.net/…`
- WHEN the blob is downloaded
- THEN the CDN is asked without the token, and the blob is read

#### Scenario: A redirect to plain http
- GIVEN a DAV server answering 301 to `http://…`
- WHEN the request follows
- THEN it fails before anything is sent there

#### Scenario: A server redirecting to itself
- GIVEN a server answering every request with a redirect
- WHEN the request follows
- THEN it fails after five redirects

### Requirement: A server-named href stays on the server
A principal or home set a DAV server names SHALL be used only on the origin that named it, or over https on a sibling host under the same site, a generic second level (`co.uk`) being no site.

#### Scenario: iCloud's calendar home
- GIVEN `caldav.icloud.com` naming its calendar home on `p42-caldav.icloud.com`
- WHEN calendars are listed
- THEN the home set is used
- AND one named on another site is refused

### Requirement: An OAuth credential is renewed once at a time
Renewing an OAuth credential SHALL hold that credential's process-wide lock and read the stored credential first: when another caller renewed it meanwhile, that renewal SHALL be reused rather than spending the retired refresh token, and a renewal SHALL be written onto the stored account, never onto a caller's stale copy.

#### Scenario: Three mail workers find the token expired
- GIVEN three sync workers holding the same account with refresh token R0
- WHEN each answers 401 and renews
- THEN one token request is made, and all three continue with its tokens

### Requirement: Setup accepts encrypted servers only
The connection flow SHALL keep a discovered HTTP endpoint only when it is https, SHALL resolve DAV SRV records secure-only, and SHALL fetch an autoconfig named by a `mailconf=` TXT record only over https. A typed mail server SHALL be `imaps` (a bare host gets port 993), a typed DAV URL `https`, a typed submission server `smtps` or `smtp`; anything else SHALL be refused on its card, nothing sent.

#### Scenario: A typed plain IMAP server
- GIVEN the advanced setup with mail entered by hand as `imap://mail.example.org:143`
- WHEN Connect is pressed
- THEN the card says only encrypted servers are accepted, and no password is sent

### Requirement: The user sees where a password goes
A sign-in card asking for a password or token SHALL name, before it is sent, each endpoint it signs in to by scheme and host, and SHALL flag in the error colour an endpoint whose host is outside the address's domain.

#### Scenario: Mail hosted elsewhere
- GIVEN `jane@example.org` whose autoconfig names `imaps://imap.fastmail.com:993`
- WHEN the sign-in card shows
- THEN it reads that the password is sent to `imaps://imap.fastmail.com:993`, not on `example.org`, flagged

### Requirement: OAuth servers are checked
The issuer, every endpoint its metadata names and the authorization URL the browser opens SHALL be https (http only on a loopback host); the metadata's `issuer` SHALL be the issuer asked for (RFC 8414 section 3.3); an issuer-flow redirect whose `iss` names another issuer SHALL be refused before the code is exchanged (RFC 9207). No error SHALL carry the redirect URL, which holds the code.

#### Scenario: A mix-up redirect
- GIVEN a grant started at issuer A
- WHEN the redirect comes back with `iss` naming issuer B
- THEN it is refused and nothing is exchanged

## MODIFIED Requirements

### Requirement: A provider grant is the app's own
A browser grant against Google's or Microsoft's authorization server SHALL run with the app's registered client only when every endpoint the token will be presented to (each domain's base and the submission server) is that provider's own, and SHALL then ask only for that provider's fixed scopes; any other SHALL run as a generic grant. It SHALL NOT send an RFC 8707 resource. Domains SHALL share one grant only when their scopes address the same API.

#### Scenario: Microsoft mail and calendars
- GIVEN mail and calendars both connected through Microsoft
- WHEN the sign-in sequence runs
- THEN it asks for two consents, Outlook's and Graph's

#### Scenario: A configuration pairing Google's sign-in with another host
- GIVEN an autoconfig naming Google's authorization endpoint beside `imaps://imap.evil.example:993`
- WHEN the browser card runs
- THEN the app's Google client is not used, the generic client page opens instead

## REMOVED Requirements
