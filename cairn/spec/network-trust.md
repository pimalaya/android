---
cairn: spec
capability: network-trust
status: current
---

# Network trust

Where a credential may go. A password or token leaves only over TLS, follows a redirect only to the server it belongs to, follows a server-named href only within that server's site, and a rotating refresh token is spent once. The rules are enforced where the bytes leave, in the bridge and again in the transport, so no forgotten call site can bypass them.

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
