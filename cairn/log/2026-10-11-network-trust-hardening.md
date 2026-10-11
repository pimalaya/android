---
cairn: log
change: network-trust-hardening
landed: 2026-10-11
---

# Credentials go only where they are meant to, encrypted

Capabilities moved: network-trust (new: a credential never leaves unencrypted, redirects bounded and kept home, server-named hrefs kept on the server, one renewal per credential at a time), onboarding (setup accepts encrypted servers only, the user sees where a password goes, OAuth servers are checked, a provider grant is the app's own only on the provider's hosts).

The fixes of the S2 network-trust audit's high findings and most of its medium ones. `rust/src/client/trust.rs` holds both rules: `refuse_cleartext_credential`, which `Client::write` (every byte the bridge sends) applies, tracking the URLs STARTTLS upgraded, and `Redirects`, which the WebDAV and JMAP redirect runners, now generic over the throttle's `Wire`, apply: five hops, never https to anything else, another origin refused for credentialed requests and followed without the `Authorization` header for JMAP blob downloads. `trusted_href` keeps principals and home sets on their origin or an https sibling of the same site. Discovery resolves DAV SRV secure-only and drops http `mailconf=` URLs; OAuth metadata must name the issuer asked for and https endpoints, token and registration endpoints and authorization URLs must be https, and `validate_redirect` checks RFC 9207 `iss` against the issuer persisted with the pending grant, its errors no longer carrying the redirect URL. On the Java side `Endpoints` gates typed and discovered endpoints and names them on the sign-in card with an other-domain flag, `Oauth.shipped` allows the app's Google and Microsoft clients only on their host allowlists with fixed scopes, `OauthFlow.browse` opens https only, `TokenRenewal` serializes refreshes per credential and reuses a renewal stored meanwhile, and `Transport` refuses credentials on plain sockets and turns an unparsable host into a `PimalayaException`. M6 and the remaining low findings stay open in the change's tasks.
