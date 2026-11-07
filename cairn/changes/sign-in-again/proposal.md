---
cairn: change
id: sign-in-again
status: active
created: 2026-11-07
---

# An account signs in again

## Why

An account whose password changed, whose app password was revoked or whose browser grant ended (Google's 7-day refresh tokens while the client is in Testing, a consent withdrawn) cannot sign in again. Its only way back is to be removed, losing its outbox, staged edits and retained items, and set up anew. Its server settings cannot be changed either, the sending server aside (production-release §4; account-settings-page left "Sign in again" to a change of its own).

## What

The owner's direction (2026-11-07): the onboarding's *Review your domains* page (one-onboarding-path), opened on the account.

- **Where from.** The account settings' *Sign-in* card leads with a *Sign in again* row, over its servers. An account whose last sync was refused for its sign-in says so in its drawer card, *Sign in again* in the error tone, a tap opening the same page; the sync's report names it too. Refused for its sign-in is a 401 that survives the token's renewal, an OAuth `invalid_grant`, or an IMAP or SMTP authentication failure; a sync that signs in clears it.
- **The page.** *Review your domains*, under the account's address, opening at once from what the account stores: every domain it covers picked, its tile locked (a domain is removed from the *Domains* card, not here), its sentence and settings the stored ones ("With your password, at imap.example.org"). The settings button lists *Current* first, then *Auto*, what discovery finds, which runs behind, and *Custom settings*. A domain the account does not cover is offered as in the setup, connecting it adding it to the account.
- **Signing in.** As in the setup: a page per password sign-in, its login filled in; a browser grant opens the browser. Continue signs in at every server of the page; a refusal shows on its card. A browser grant SHALL sign in the account's own address (oauth-hardening L4, built here first): another one is refused, naming both.
- **What is kept.** A new password, token or grant changes nothing else: the outbox, staged edits and the store are kept, and the next sync resumes where it stood. A changed server, protocol or login for a domain is another place holding other data: a confirmation names the domain, the new server, and the changes not synced yet that are dropped; confirmed, that domain's collections are dropped and listed again from the new server. The sending server changes with no confirmation: the outbox goes out through the new one.
- **Done.** The last sign-in saves onto the stored account (AccountCommit `persist`) and returns to the account's settings; no *Review your settings* page, those settings being the page it returns to.

## Design

On the Pimalaya Android canvas, light and dark, then copied in designs/: the *Sign-in* card with its row, the drawer card refused, the domains page on an account, the server-change confirmation, the refusal of another account.
