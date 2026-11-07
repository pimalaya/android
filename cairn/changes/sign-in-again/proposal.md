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
- **What is kept.** A new password, token or grant changes nothing else: the outbox, staged edits and the store are kept, and the next sync resumes where it stood. A changed server, protocol or login for a domain is another place holding other data: picking it in the settings menu raises a confirmation naming the domain, the new server, and the changes not synced yet that are dropped; confirmed, that domain's collections are dropped and listed again from the new server. The sending server changes with no confirmation: the outbox goes out through the new one.
- **Done.** The last sign-in saves onto the stored account (AccountCommit `persist`) and returns to the account's settings; no *Review your settings* page, those settings being the page it returns to.

## Design

On the Pimalaya Android canvas (version 115, 2026-11-07), light and dark, copied in designs/: AccSetSignIn (the *Sign-in* card with its row), DrawerSignIn (the refused card, its pill showing the error glyph), SignAgainDomains (the address under the title, covered tiles locked, their settings still open), SignAgainMenu (*Current* first), SignAgainServer (the confirmation, raised when a server-changing option is picked), SignAgainWrong (the refusal under the browser sign-in's note).

## Built (2026-11-07), decisions the design left open

- **Refusals** are kept per account and per place (mail, sending, contacts, calendar), cleared by a sync that signs in at that place, by a sign-in again and by the account's removal (sync/SignInRefusals). An SMTP AUTH refusal and an OAuth `invalid_grant` now read as a 401, as an IMAP refusal already did; a refused send stays queued. A failed sync that was a refusal shows *Sign-in refused*, naming the accounts, with *Sign in again*.
- **The page.** A covered domain is locked, its settings still open; *Mail (sending)* is locked when the account sends, offered when it sends nowhere. A domain the account does not cover starts unpicked, "Looking for its settings" until discovery lands, and is added with every mailbox but junk and trash, every book and calendar, no phone mirror. *Current* rebuilds a Google or Microsoft grant from the stored one (Google's scope narrowed to the domain); another provider's grant waits for discovery.
- **A move** is a base URL that differs past its user part, trailing slash and case, or a password login that differs past case (confirmed on the password page's Continue). Its count is the changes not pushed yet (count_owing.sql), the outbox aside. A mail move keeps the outbox, its staged sent copies dropped with the old mailboxes; a contacts move drops the address books and their phone accounts; a calendar move forgets its phone calendars. Saving waits while a sync runs; a moved or added domain lists again when its tab opens.
- **The identity (oauth-hardening L4)** is checked on every browser grant, the first setup included: Google by the Gmail profile, else the primary calendar's id (the app asks no `openid` or `email` scope); Graph by `/me`; JMAP by the session's username. Where none can be read the grant is allowed: Google contacts alone, Outlook IMAP and SMTP, Graph without `User.Read` (a 403), generic OIDC (io-oauth drops the `id_token`), a JMAP username without `@`.

## Aliases (2026-11-07)

The identity accepts the account's own aliases, as the owner asked: a gmail.com or googlemail.com address spelt with dots, a `+` tag or the other domain (never a Workspace domain, whose dots count); a verified Gmail send-as address, for a grant holding the mail scope; a Graph `smtp:` proxy address; a JMAP identity, for a session that submits. An unverified send-as address (pending, rejected, expired) is not taken. Still refused: a Workspace alias on no send-as list or under a calendar-only grant, a JMAP wildcard identity (`*@domain`), a Microsoft alias missing from `proxyAddresses`.

## Open

- Whether Graph answers `$select=proxyAddresses` for a personal Microsoft account: an error there would read as no identity, letting every personal Microsoft grant through unchecked. To check on the Microsoft test account.
