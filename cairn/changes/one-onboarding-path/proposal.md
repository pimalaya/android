---
cairn: change
id: one-onboarding-path
status: active
created: 2026-11-04
---

# One onboarding path

## Why

The connection flow forks right after the address: the standard setup (a tick per domain, nothing technical) or the advanced one (a radio list of configurations per domain, manual entry last). The fork is asked before the user has seen what was discovered, so they choose blind, and the two branches do not look alike: checkboxes on one, switches over radio rows on the other. Options that apply to a domain (notifications, the phone mirror, which addressbooks) are scattered between the domain screen and a later step, and nothing syncs until the flow ends and a tab is opened, so a three-domain account starts empty three times.

## What

One path for everyone, the technical choices folded behind the row they concern rather than behind a mode.

### 1. What to connect

After the address and discovery, one screen: one card of the domains the discovery found, mail, contacts and calendars in the bar's order, each a checkbox row and nothing else (nothing ticked to begin with, Continue disabled until one is). How a domain signs in and what it syncs are the next pages'.

Checkbox or switch, one rule across onboarding and settings: a **checkbox picks items** from a set and is confirmed by a button (domains, collections); a **switch turns a setting** on or off and applies at once (notifications, the phone mirror). The phone mirror's current checkbox in onboarding becomes a switch.

### 2. The sign-in plan

One page, the advanced setup's sign-in page (advanced-onboarding-pages) made everyone's: the plan the ticked domains need, as sign-in entries, one card each. Each domain starts on its best-ranked option (the standard setup's ranking: the provider's own API at Google and Microsoft, then JMAP over IMAP, SMTP, CardDAV and CalDAV, then OAuth over an API token over a password), and the domains whose options share a sign-in share an entry: one browser grant for every domain of one authorization server and audience (three at once at Google, Microsoft or Fastmail), and one password or token card per server otherwise: a JMAP session is one server for every domain it serves, while IMAP, SMTP, CardDAV and CalDAV are four, even on one host (Posteo: four cards). From the second password card on, a switch *Same login and password as Mail* starts off and turns on once that card is signed in (owner, 2026-11-04: each card is the user's own step, nothing signs in together). Turning it on signs the card in with that card's login and password, against its own server, with no sign-in button of its own; turning it off undoes it and shows the card's fields. With two or more earlier password cards, the switch's label picks which one. A linked card that fails to sign in unlinks, its fields shown. A signed card folds to its header and a *Sign in again* button, drawn as the sign-in button it replaces.

The card no longer lists where the password goes in a line of its own, flagged when outside the address's domain (owner, 2026-11-04): the subtitle names the host, and the flag fired on every card of a provider whose servers sit on another domain than its addresses (posteo.net addresses, posteo.de servers).

A card names the domains it covers and says in a plain sentence how they sign in, for example "With your Google account, in the browser", "With your password, at fastmail.com", "With an API token, at fastmail.com". No protocol, host or port on it. Under the sentence, what the sign-in needs: a login and a password, a token, or the browser button.

- A **tune button** at the top right of each card (owner, 2026-11-04) opens one menu, which merges the More and Edit settings first planned here: the other options discovery found for each domain of the card, worded the same way, then *Custom settings*. Picking an option for a domain moves that domain out of the card: into an unsigned card of the same sign-in, or into a card of its own where its old card stood, nothing typed lost.
- **Custom settings** turns the domain's card into the form, in the card itself: server and port (mail, sending), STARTTLS (sending), server URL (contacts, calendars), and an API token switch, prefilled from the discovered endpoint, above the login and password, plain refused as today. On a card covering several domains it moves that domain out first. The form's own tune menu offers *Use discovered settings*. A ticked domain discovery found nothing for is planned as a card already showing the form. There is no separate manual entry: it is this form. JMAP is not offered in the form, only as a discovered option.

Sending is an entry of its own, *Mail (sending)*, when mail reads over IMAP signed in with a password or a token: SMTP is a second server and may take another login (owner, 2026-11-04), so its card signs in against the submission server, an SMTP authentication and nothing sent. Its tune menu lists the other submission endpoints discovered, *Custom settings* and *Do not send from this account*. A browser grant reading mail still covers sending (its scopes merged), and JMAP, Gmail and Graph submit through the session they read with, so neither has a sending card. An address publishing none starts on *Do not send from this account*, with no sending card.

### 3. One page per domain

Once signed in, one page per ticked domain, in the bar's order, built from the account settings sections of that domain, the same builders, so what onboarding sets is what settings shows later:

- **Mail**: the mailboxes (checkboxes), *Notify new mail* (switch, permission asked when turned on) and the download window (all mail or the last 1, 3, 6, 12 or 24 months), set before the first sync since it bounds it.
- **Contacts**: the addressbooks (checkboxes) and *Show in phone contacts* (switch, permission asked when turned on).
- **Calendars**: the calendars (checkboxes) and *Show in phone calendar* (switch, permission asked when turned on).

A domain with a single collection shows no collection selection: that collection is taken. Each page ends with Continue, Finish on the last.

### 4. Each domain syncs as its page is done

The account is saved once its sign-ins succeed, before the domain pages, so a domain can sync while the user fills the next page. Continue on a domain's page starts that domain's first sync in the background, with nothing shown during the flow (no dock, no progress). Backing out of the flow after the account is saved removes the account and what it stored.

Finishing lands on the first ticked domain in the bar's order, where the sync dock shows what is still running. A first sync that failed or did not start stays owed to that domain's tab, as today.

## Open points

- **A flow cut off by the process dying** after the account is saved. Proposed: the account carries a setup-pending mark until Finish; on the next launch an account still marked is removed, as backing out does, so no half-configured account is left behind.
- **Mail's mailboxes** (owner, 2026-11-04): mail's page asks which mailboxes sync, every one ticked but the junk and the trash, none asked with a single one. The choice stands in for the inbox-alone default a fresh account other than Gmail starts with (MailStore.replaceMailboxes), unticked mailboxes hidden in the mail filter, which is also the sync scope. A mailbox the server adds later starts ticked.
- **Parallel first syncs**: the drawer's sync runs contacts, then mail, then calendars. Pipelined first syncs need the coordinator to run one domain's pass while another's runs, for one account; to be checked in `SyncCoordinator` before step 4.
- **Fastmail calendars over OAuth** fail with `invalid_target` (device log, 2026-11-04): RFC 6764 resolves the per-user shard (`d709745.caldav.fastmail.com`), io-pim-discovery keeps it over PACC's `caldav.fastmail.com` (b07dc35), the shard serves no RFC 9728 metadata, and the grant falls back to the shard URL as its resource (`SetupPlan.resourceOf`); `caldav.fastmail.com`'s metadata names `https://caldav.fastmail.com/dav/calendars`. The bridge does not carry discovery's resource to `AuthMethod` either. A fix of its own, in io-pim-discovery and the bridge, outside this change.
- **Sending signs in on its own** (owner, 2026-11-04): an account stores a second credential for submission when its sending card signed in with another login or password than mail's (`AccountConnection` names it beside mail's credential, the bridge account JSON carries `submitLogin` and `submitSecret`); the same login and password share mail's credential, one secret stored.
- The three onboarding changes left active were folded and archived on 2026-11-04 (5d75c11).

## Steps

Landed one at a time, each installed and checked on the device before the next:

1. One path: the mode question goes; the domain screen is checkbox rows alone, Continue leading to the sign-in page for everyone.
2. The sign-in plan: one card per server, the "same password as" switch, sending as its own card, the tune menu (alternatives splitting a domain out, custom settings in the card), each card's sentence.
3. The domain pages, from the account settings sections, the collection selection skipped for a single collection; the switch rule applied.
4. The account saved after sign-in, a first sync started per finished page, the account removed on back-out or an unfinished setup.
5. Landing on the first ticked domain.
