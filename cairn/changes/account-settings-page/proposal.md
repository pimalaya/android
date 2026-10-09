---
cairn: change
id: account-settings-page
status: active
created: 2026-10-09
---

# Account settings: one page of cards, every change applied at once

## Why

`AccountSettings` builds the screen from bare rows and works under two rules at once:

- The sending server, the sync period and the download policy are committed when picked.
- The account switch and the per-addressbook switches are staged until the check FAB (`account_fab`) commits them. Nothing on the screen says which is which.

The rows are raw: Spinners showing an array item as their own label, CheckBoxes beside long sentences ("Download messages on metered networks too"), and 1dp `@color/surface` lines as separators. The addressbooks hide behind "Enable advanced settings per addressbook", a checkbox that is a fold, not a setting. The page never says which account it is beyond the bar's title, and deleting is a bin in the bar.

The contact and entry pages already have a vocabulary for this (`Sections`: a label over one rounded card, rows of title and value); the settings page should speak it.

## What

### Top of the page

- Bar: the round close button (`button_tonal`) alone. The address appears as the bar's title once the identity block has scrolled away, as the lists hand their large title to the bar.
- **Identity**, as the drawer's account card draws it: the 52dp disc, the address (22sp bold), the domains line ("Mail · Contacts · Calendar"), and the sync pill (`syncPill`: "Synced 5 minutes ago", "Deactivated", ...).
- **Use this account**: one card, a switch, and the line "Off: nothing syncs and the lists leave it out. Its data stays on this phone." (`AccountActivation`).
- **Mail** (`Sections` card, mail glyph), for an account covering mail:
  - "Sync period", value "The last 3 months": opens the choice dialog.
  - "Download messages", value "When opened": opens the choice dialog.
  - "Also on mobile data", a switch, line "Background downloads wait for Wi-Fi otherwise" (`mail_offline_metered`).
  - "Send through", value the SMTP host or "No server": opens the existing sending-server dialog. Hidden for Graph and Gmail, as today.
  - "Your name", first, value the name or "Not set": a one-field dialog. Mail goes out as `Name <address>` (the bridge's `Draft.fromName`, which the composer never filled); empty sends the bare address. Kept per account in `SenderName`. Agreed on review, not in the designs.

### Further down

- **Addressbooks** (contacts glyph), replacing the advanced fold. One row per addressbook with a switch (`book_enable`). On, two checkbox rows indented under it: "Sync with the server" (`book_remote_sync`), "Show in the phone's contacts" (`book_local_sync`). Off, the row's line reads "Not on this phone".
- **Servers** (work glyph), read only: one row per server, the host as title, "domains · protocols · method" as line ("Contacts, Calendar · CardDAV, CalDAV · Password"). Display only, from what the account stores.
- **Remove this account**: a full-width tonal pill (`button_tonal`, 56dp) in `?android:attr/colorError`, with a line under it saying what happens. It replaces `account_delete` in the bar and keeps the confirmation dialog. The line reads "Its contacts stay on this phone." only for a contacts-only account: removal moves the contacts into the on-device book but forgets the mail and the events, so an account covering those reads "Its contacts move to this phone. Its mail and events are removed."

### Choices

"Sync period" and "Download messages" open a single-choice dialog: title, one line of context ("Older mail stays on the server. Narrowing frees space now."), radio rows, Cancel. A tap applies and closes. The values read as values, not commands: "All mail", "The last month", "The last 3 months", "The last 6 months", "The last year", "The last 2 years"; the policies as today.

### One rule

Every change applies when made. The check FAB and the staging (`BookSettings`, `accountEnabled`, `save`) go. No switch asks first: the account switch keeps the data, and a book's switches only flip its flags (`CardStore.setBookState`), so its contacts and its queued changes wait for it to come back on.

Text fields in the dialogs are `PillField`s, as on the onboarding pages.

## Designs

`designs/`, 390×844:

| File | Frame |
|------|-------|
| `AccSettings.dc.html` | Top: identity, Use this account, Mail |
| `AccSettingsLower.dc.html` | Scrolled: Addressbooks, Servers, Remove |
| `AccSettingsChoice.dc.html` | The sync period dialog |

HTML mockups to read for structure, copy, sizes and colours, not code to port: build with `Sections`, the app's drawables and theme attributes.

## Out of scope

- A "Sign in again" action for a changed password: not built today, to be proposed on its own.
- Calendars and mailboxes per collection: they stay on the filter page.
