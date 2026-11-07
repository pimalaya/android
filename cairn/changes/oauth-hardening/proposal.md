---
cairn: change
id: oauth-hardening
status: active
created: 2026-11-07
---

# OAuth hardening

## Why

The S2 network-trust audit's OAuth findings left open by network-trust-hardening (its tasks.md, Open), gathered here as one plan by the owner's request (2026-11-07). The shipped Google and Microsoft clients are pinned to their providers' hosts already (H3, first part); what is left concerns mostly the grants discovery finds for other servers, and the identity a grant returns.

## What

- **H3, an issuer paired with another host.** Discovery reads which authorization server goes with which mail or DAV server from the domain's own configuration. A forged one can pair an honest issuer with a host its author runs: the user signs in at the honest provider, and the app hands that provider's token to the other host. Fix: take the pairing from the resource server itself (RFC 9728 protected-resource metadata) where it publishes one, else require the issuer and the server to share a registrable domain (the public-suffix list of network-warnings).
- **M1, `iss` required where advertised.** The redirect's `iss` (RFC 9207), which tells the issuer that answered from another one (a mix-up attack), is checked when present but not required when the metadata advertises `authorization_response_iss_parameter_supported`. io-pim-discovery 0.9 drops that field, so it is read again from the metadata the app fetches.
- **M2, https only in io-oauth.** The app refuses http endpoints before calling io-oauth (`trust::require_https`); the library still accepts them. Fixed in io-oauth, so every Pimalaya tool gets it.
- **L3, RFC 8707 `resource`.** Name the server a token is for on the token exchange and on every refresh, so a token is not replayed against another server of the same provider.
- **L4, the identity a grant returns.** After a browser sign-in, check that the account signed in is the address being set up (Google's userinfo, Graph's `/me`, the JMAP session's `username`). Signing in as bob@ for alice@'s account would otherwise point alice@'s store at bob@'s data. The re-sign-in (sign-in-again) needs it first.
- **L5, the redirect's order.** An error redirect is handled before its `state` is checked, so a forged one cancels a sign-in in progress; check `state` first. The loopback listener takes the first connection to its port, which another app on the phone can make first: accept only the request carrying the expected path and `state`, keep listening otherwise.

## Out

Credentials in clear, redirects and hrefs are network-trust-hardening's, landed; the remaining network findings are network-warnings'.
