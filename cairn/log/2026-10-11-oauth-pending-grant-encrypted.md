---
cairn: log
change: oauth-pending-grant-encrypted
landed: 2026-10-11
---

# The in-flight OAuth grant is sealed on disk

Capabilities moved: onboarding (an in-flight OAuth grant never sits on disk in clear).

The grant persisted across the browser hop (client id and custom secret, redirect, CSRF state, PKCE verifier, token endpoint, server, address) was written to the plaintext preferences `pimalaya.oauth`. `PendingGrantStore` now keeps it as one AES-GCM blob under `SecureStore`'s Keystore key, through `SecureStore.seal` and `open`, and aborts the grant when sealing fails. Opening it wipes what an older version left in clear, and drops a blob that no longer opens. The redirect drops the grant before the exchange, an abort drops it, and `OauthFlow.cancel`, called when the connection flow is closed or stepped back in, drops it too. Tests run the real sealing over a software "AndroidKeyStore" provider under Robolectric.
