---
cairn: change
id: advanced-onboarding-pages
status: active
created: 2026-10-09
---

# Advanced onboarding: pages instead of dialogs, one sign-in per server

Follows `standard-onboarding-one-password` and builds on what it landed (the round fields with their hint inside, `field_background`, `PrimaryButton`, `TextLink`, `PageTitle`, the round back button with no bar).

## Why

The advanced setup is where the user who knows their servers goes: different passwords, a manual server, an API token, their own OAuth client, a choice of addressbooks. Today it still runs in the old shape:

- The domain step lists bare `RadioButton`s with one-line labels packing protocol, host and method together ("JMAP (api.fastmail.com) · OAuth 2.0"), under an `android.widget.Switch`, outside any card.
- The sign-in sequence (`runNextAuthStep`) is a chain of `AlertDialog`s titled "Step %1$d/%2$d · %3$s · %4$s": manual server, password (login and password), API token, OAuth client (six fields, scrolling). One dialog per step, each hiding what came before and what is next.
- The addressbooks step is a bare list of `CheckBox`es.

The standard setup now speaks in pages, cards and round fields; the advanced one should speak the same language, and its sign-ins should be seen together, since seeing them together is what lets the user notice two servers sharing a password.

## What

### 1. Server and sign-in per domain (`panel_domain`, advanced mode)

- Title (`PageTitle`): "How should each one connect?" Message: "Pick a server and a sign-in for each. Switch off what you do not want on this phone."
- One card per domain (`card_group`, 22dp, margins 12dp between cards): a header row with the domain's glyph on a 32dp tile, its name (16sp bold), and its switch at the end. A switched-off domain shows its header alone.
- Under a switched-on header, one radio row per option, 56dp min, a hairline (`divider_light`) between rows inset to the text: the protocol on line one (16sp, "IMAP"), "server · method" on line two (13sp secondary, "imap.fastmail.example · Password"). "Enter settings manually" last, its line two "Server, login and password".
- Mail's card carries a "SENDING" sub-header (the `SectionHeader` style) and its radio rows: "SMTP" over "smtp.fastmail.example:465 · Password", "Do not send from this account", "Enter settings manually". Same visibility rules as today (`sends`, `offers`).
- Domains start as they do today: switched off, unless the standard screen the user came from had them ticked.
- `PrimaryButton` "Continue", pinned at the bottom.

### 2. One sign-in page (new step, replaces the dialog chain)

- Title "Sign in". Message: "One sign-in per server. Domains sharing a server share it."
- One card per planned step (`planAuthSteps`, grouped by server and login rather than one per domain): header row with the glyph(s) and the domain name(s) ("Contacts, Calendar"), line two the server and, for mail, "sends through smtp".
- Inside the card, per kind:
  - **Password**: a *Login* field prefilled with the address and a *Password* field with the show/hide toggle.
  - **API token**: one *Token* field, hint "Paste your API token".
  - **Manual entry**: *Server* (hint "host or host:port"), *Login*, *Password*. This replaces `promptManualEndpoint`.
  - **Browser**: a tonal pill "Sign in with browser" and the `TextLink` "Use my own OAuth client"; once granted, the card says "Signed in" with the accent check.
- From the second password card on, a checkbox row "Same login and password as <first domain>", off by default; on, it hides that card's fields and reuses the first card's credential.
- The sending server's manual entry (today `promptManualSubmission`) moves into Mail's card as a *Sending server* field when "Enter settings manually" was picked under SENDING.
- `PrimaryButton` "Connect" runs every step. Results come back per card, the way the standard result page reports them: a refused card shows "Password not accepted" in the error colour under its fields, and the rest keep what succeeded.

**Fields with values.** The standard setup's fields start empty, so the hint inside says what they are. Here many start filled (the login, the endpoints), and a filled field loses its hint. So a field on this page carries its name inside the pill: a fixed 84dp column in `textColorSecondary` (15sp) before the value, as the composer's `ComposeLabel` does.

### 3. Your own OAuth client (new page, replaces the dialog)

- Reached from "Use my own OAuth client", or when dynamic registration is refused.
- Line one "<Domain> · <server>", title "Your OAuth client", message "The server does not register apps on its own. Enter the client you registered there."
- Six round fields, 60dp: *Client ID* and *Client secret (optional)* empty with their hint; *Authorization endpoint*, *Token endpoint*, *Scope*, *Redirect URI* prefilled, each with its name as a 12sp line above the value inside the pill (too long for the 84dp column).
- `PrimaryButton` "Continue in browser"; back returns to the sign-in page with nothing lost.

### 4. Which addressbooks? (`panel_books`)

- Line one "Signed in · <address>" with the accent check, title "Which addressbooks?", message "The ones you pick sync to this phone. You can change this later in the account's settings."
- One card, a row per addressbook (name 16sp, checkbox at the end, hairline between rows), checked by default as today.
- `PrimaryButton` "Finish", landing on the mail list as the standard setup does.

## Designs

`designs/`, one `.dc.html` per frame (390×844):

| File | Frame |
|------|-------|
| `AdvServices.dc.html` | 1. Server and sign-in per domain |
| `AdvSignIn.dc.html` | 2. Sign-in page, two password servers, the "same as" checkbox |
| `AdvSignInMixed.dc.html` | 2b. Same page: manual server, API token, browser sign-in |
| `AdvOauthClient.dc.html` | 3. Your own OAuth client |
| `AdvBooks.dc.html` | 4. Which addressbooks? |

They are HTML mockups to read for structure, copy, sizes and colours, not code to port: build with the app's views, drawables, theme attributes and the styles `standard-onboarding-one-password` added.

## Out of scope

- Discovery and the option ranking, unchanged.
- The standard setup, unchanged.
