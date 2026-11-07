---
cairn: delta
change: oauth-hardening
---

# Delta

Folds into spec/network-trust.md.

## ADDED Requirements

### Requirement: A browser grant signs in the account being set up
After a browser grant, the app SHALL read the identity the grant signed in and SHALL refuse the grant when it is not the account's address, naming both, so no account stores another one's token.

#### Scenario: Another account signed in the browser
- GIVEN the setup of alice@example.com
- WHEN the browser signs in as bob@example.com
- THEN the setup refuses the grant, saying bob@example.com signed in for alice@example.com

## MODIFIED Requirements

## REMOVED Requirements
