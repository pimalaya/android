---
cairn: delta
change: oauth-pending-grant-encrypted
---

## ADDED Requirements

### Requirement: An in-flight OAuth grant never sits on disk in clear
The app SHALL persist a grant that goes through the browser only sealed under the same Keystore key as the accounts, client secret, CSRF state and PKCE verifier included, and SHALL abort the grant rather than persist it in clear. It SHALL drop the persisted grant once the redirect arrives, before the code is exchanged, when the grant aborts, and when the user leaves or steps back in the connection flow. A grant an older version persisted in clear SHALL be wiped when the app next starts, and one that no longer decrypts SHALL be dropped.

#### Scenario: The process dies in the browser
- GIVEN a Google grant started, the app reclaimed while the user signs in
- WHEN the redirect brings the app back
- THEN the grant is restored from its sealed copy and dropped from disk before the exchange

#### Scenario: The user walks away
- GIVEN a grant persisted for the browser
- WHEN the user closes the connection flow or steps back in it
- THEN the persisted grant is gone, and a late redirect redeems nothing

#### Scenario: An upgrade over a plaintext grant
- GIVEN the plaintext preferences an older version wrote for a grant
- WHEN the app starts
- THEN they are wiped

## MODIFIED Requirements

## REMOVED Requirements
