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

After the address and discovery, one screen: one card per domain the discovery found, mail, contacts and calendars in the bar's order, each a checkbox row (nothing ticked to begin with, Continue disabled until one is).

Under a ticked domain, one row says in a plain sentence how it connects, the best-ranked option selected by default (the standard setup's ranking: the provider's own API at Google and Microsoft, then JMAP over IMAP, SMTP, CardDAV and CalDAV, then OAuth over an API token over a password). For example "In your browser, with your Google account", "With your password, at Fastmail", "With an API token, at fastmail.com". No protocol, host or port on the row.

- **More** unfolds the other options discovery found for that domain, one radio row each, worded the same way, the selected one marked. Picking one folds the list back.
- **Edit settings**, last in the unfolded list, opens one form prefilled from the selected option: protocol, host, port, security (TLS or STARTTLS, plain refused as today) and sign-in method. Saving it makes it the domain's selected option. When discovery found nothing for a domain, the domain is still listed and its row opens the same form, empty. There is no separate manual entry: it is this form.

Mail's card carries sending the same way: a checkbox *Send mail*, ticked when discovery found a submission endpoint, its sentence row ("Through Fastmail"), More and Edit settings. An address publishing none shows it unticked with its sentence row opening the empty form.

Checkbox or switch, one rule across onboarding and settings: a **checkbox picks items** from a set and is confirmed by a button (domains, sending, collections); a **switch turns a setting** on or off and applies at once (notifications, the phone mirror). The phone mirror's current checkbox in onboarding becomes a switch.

### 2. Sign in

One sign-in page, the advanced setup's (advanced-onboarding-pages): one card per server and login, a password card after the first offering the first one's credentials, browser grants as a button per card, one grant per authorization server and API. It is the only sign-in step for everyone.

### 3. One page per domain

Once signed in, one page per ticked domain, in the bar's order, built from the account settings sections of that domain, the same builders, so what onboarding sets is what settings shows later:

- **Mail**: *Notify new mail* (switch, permission asked when turned on) and the download window (all mail or the last 1, 3, 6, 12 or 24 months), set before the first sync since it bounds it.
- **Contacts**: the addressbooks (checkboxes) and *Show in phone contacts* (switch, permission asked when turned on).
- **Calendars**: the calendars (checkboxes) and *Show in phone calendar* (switch, permission asked when turned on).

A domain with a single collection shows no collection selection: that collection is taken. Each page ends with Continue, Finish on the last.

### 4. Each domain syncs as its page is done

The account is saved once its sign-ins succeed, before the domain pages, so a domain can sync while the user fills the next page. Continue on a domain's page starts that domain's first sync in the background, with nothing shown during the flow (no dock, no progress). Backing out of the flow after the account is saved removes the account and what it stored.

Finishing lands on the first ticked domain in the bar's order, where the sync dock shows what is still running. A first sync that failed or did not start stays owed to that domain's tab, as today.

## Open points

- **A flow cut off by the process dying** after the account is saved. Proposed: the account carries a setup-pending mark until Finish; on the next launch an account still marked is removed, as backing out does, so no half-configured account is left behind.
- **Mail's mailboxes**: every mailbox, inbox first, as today. No mailbox picker on the mail page.
- **Parallel first syncs**: the drawer's sync runs contacts, then mail, then calendars. Pipelined first syncs need the coordinator to run one domain's pass while another's runs, for one account; to be checked in `SyncCoordinator` before step 4.
- The three active onboarding changes (advanced-onboarding-pages, standard-onboarding-one-password, onboarding-options-ask-on-switch) have their tasks done but were never folded or archived. They are folded as they stand before this one lands, so its delta reads against a spec that says what the app does.

## Steps

Landed one at a time, each installed and checked on the device before the next:

1. One path: the mode question goes; the domain screen gets its checkbox rows and their sentence rows, the best option selected, no More yet.
2. More and Edit settings: the alternatives in place, the form prefilled or empty, sending the same way.
3. The domain pages, from the account settings sections, the collection selection skipped for a single collection; the switch rule applied.
4. The account saved after sign-in, a first sync started per finished page, the account removed on back-out or an unfinished setup.
5. Landing on the first ticked domain.
