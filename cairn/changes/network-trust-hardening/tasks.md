---
cairn: tasks
change: network-trust-hardening
---

# Tasks

Rust paths are relative to rust/src/, Java to android/*/src/main/java/org/pimalaya/.

- [x] H1 client/trust.rs `Redirects`: 5 hops, https never to http, cross-origin refused or anonymous; client.rs `run_webdav_redirect` and client/jmap.rs `run_jmap_redirect` over `Wire` (downloads anonymous, session and upload refuse)
- [x] H2 client.rs `Client::write` refuses cleartext credentials (`refuse_cleartext_credential`, STARTTLS-upgraded URLs tracked); client/imap.rs refuses `imap://` before the greeting
- [x] H2 client/discovery.rs DAV SRV `with_secure_only(true)`; principal and home sets (discovery.rs, caldav.rs) through `trusted_href`
- [x] H2 Java: `Endpoints` (manual URLs, submission, https HTTP endpoints), `OnboardingFlow.connectAll` refuses on the card, `AccountSettings` submission prompt; `Transport.write` refuses a credential on a plain socket
- [x] H3 `Oauth.shipped` host allowlists and `Oauth.fixedScope`; `OnboardingFlow.startGrant` falls back to a generic grant
- [x] M1 `oauth::validate_redirect` checks RFC 9207 `iss` against the issuer persisted with the pending grant; `trusted_metadata` compares the RFC 8414 issuer
- [x] M2 `trust::require_https` on issuer, metadata endpoints, token and registration endpoints, authorization URL; `OauthFlow.browse` opens https only
- [x] M3 sign-in card line naming scheme and host, other-domain flag (en, fr)
- [x] M4 `mailconf=` URL https only
- [x] M5 `TokenRenewal` per-credential lock with stored-credential reuse, `SecureStore.renew`
- [x] L7 `Transport` typed failure on an unparsable host
- [x] Carry-over: the redirect validation error no longer names the redirect URL (code)
- [x] Tests: trust_tests.rs (scripted redirects, cleartext rule, hrefs, https), oauth.rs, imap_tests.rs; EndpointsTest, OauthTest, TokenRenewalTest, TransportTest
- [x] CHANGELOG.md Security; spec folded; log written
- [x] Build: cargo fmt, clippy (no warning), test; `:app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest`; `cairn/verify.sh`

## Open

- [ ] M6 duplicate writes (ambiguous SMTP/JMAP/Graph/Gmail sends, IMAP COPY fallback rerun, append retry, Graph event `transactionId`)
- [ ] H3 issuer flow: a config pairing an honest issuer with another host still gets that issuer's token (needs RFC 9728 protected-resource metadata or a registrable-domain rule); the M3 flag covers passwords only
- [ ] M1 `iss` is checked when present but not required when metadata advertises `authorization_response_iss_parameter_supported` (io-pim-discovery 0.9 drops that field); shipped Google and Microsoft grants keep their own redirect URIs and no expected issuer
- [ ] M2 io-oauth itself still accepts http endpoints (library change)
- [ ] L1 throttle resend: read timeout treated as loss for PUT/DELETE, 503 retry of POST
- [ ] L2 discovery walk redirects to non-http schemes and internal hosts (io-pim-discovery)
- [ ] L3 RFC 8707 `resource` on token exchange and refresh
- [ ] L4 identity check after a grant
- [ ] L5 error redirects before the state check, loopback first connection
- [ ] L6 TLS 1.2 floor on API 26-28
- [ ] L8 DoH without DNSSEC, PACC digest trust documented
- [ ] `trusted_href` approximates a site without a public-suffix list (generic second levels like `co.uk` refused)
