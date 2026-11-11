---
cairn: log
change: google-grant-asks-only-its-scopes
date: 2026-11-11
---

# A Google grant no longer takes back the account's older scopes

Part of oauth-verification (tasks §2, the consent screen item), from the scope audit's side finding: the Google authorization request sent `include_granted_scopes=true`, Google's incremental authorization, which its native-app page says installed apps do not support. On an account that had granted the production client the full `calendar` scope before narrow-google-calendar-scope (2026-11-07), the new token came back holding `calendar` again, a scope missing from the Data Access page under review.

- setup/OauthFlow.java: the extras are `access_type=offline`, `prompt=consent` and `login_hint`; the union of the account's scopes is still asked explicitly (OnboardingFlow, `Oauth.union`, narrowed by `Oauth.fixedScope`), so a re-grant still covers every domain it asks for.
- Consequence: a domain an older grant holding `calendar` signed in, and that the new sign-in did not name, is no longer covered by the new token, so it stays on that older grant (`AccountEntry.regrantable`) and the account keeps two refresh tokens until it signs that domain in again. Only grants made before 2026-11-07's narrowing are concerned: the owner's and testers' accounts.
- Test failing first: NarrowCalendarGrantTest `theGrantDoesNotTakeBackTheOlderGrantsScopes` (an account holding `calendar` signs its calendar in again, the browser URL carries no `include_granted_scopes`), failed with the flag put back, passes without it. AccountClientTest's URL builder case and oauth.rs `authorize_url_asks_google_once_for_every_domain` no longer pass the flag, mirroring the request.
- Checked: `cargo fmt --check`, the oauth.rs test, `:app:assembleDebug` and `:app:testDebugUnitTest`.

Capabilities moved: none (no spec names the request's extras).
