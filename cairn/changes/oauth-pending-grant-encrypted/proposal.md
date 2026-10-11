---
cairn: change
id: oauth-pending-grant-encrypted
status: active
created: 2026-10-11
---

# The in-flight OAuth grant is sealed on disk

## Why

A grant that goes through the browser is persisted, because the system routinely reclaims the app while the user signs in. It was written to the plaintext preferences `pimalaya.oauth`: client id, a custom client's secret, redirect, CSRF state, PKCE verifier, token endpoint, server and address. Every other credential lives sealed in `SecureStore` (AES-GCM, key in the Android Keystore). The verifier and a client secret are what redeem an intercepted code, so they deserve the same protection, and the grant stayed on disk whenever the user walked away from it.

## What

- `PendingGrantStore` keeps the grant as one AES-GCM blob in `pimalaya.oauth`, sealed under `SecureStore`'s key and scheme (`SecureStore.seal` and `open`, package-private, one key for the app). Sealing failing aborts the grant: there is no plaintext fallback.
- Opening the store wipes any key but the sealed pair, which is what a previous version left in clear; it opens with `OauthFlow`, so on the first run of this version.
- A blob that no longer opens (lost key, tampering) is dropped.
- The grant is cleared on every way out: the redirect clears it before the exchange (so on success and failure alike), an abort clears it, and leaving or stepping back in the connection flow cancels it (`OauthFlow.cancel`, from `MainActivity.cancelAuth` and `authBack`).

Out of scope: the loopback grant, which is never persisted.
