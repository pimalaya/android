---
cairn: delta
change: sign-in-again
---

# Delta

Folds into spec/onboarding.md.

## ADDED Requirements

### Requirement: An account signs in again without losing anything
An account's settings SHALL offer *Sign in again*, opening the setup's domains page on the account, filled from its stored settings, its covered domains locked. A new password, token or grant SHALL keep the account's outbox, staged edits and store. A changed server, protocol or login for a domain SHALL be confirmed, naming the changes not synced yet that it drops, and SHALL list that domain again from the new server. An account whose sync was refused for its sign-in SHALL say so in its drawer card, leading to the same page.

#### Scenario: A password changed elsewhere
- GIVEN an IMAP account with a message waiting in its outbox, whose password was changed on the provider's site
- WHEN its sync is refused, and Sign in again takes the new password
- THEN the next sync sends the waiting message, nothing else lost

#### Scenario: Another account signed in the browser
- GIVEN Sign in again on alice@gmail.com
- WHEN the browser signs in as bob@gmail.com
- THEN the grant is refused, naming both addresses, and the account keeps its old sign-in

## MODIFIED Requirements

## REMOVED Requirements
