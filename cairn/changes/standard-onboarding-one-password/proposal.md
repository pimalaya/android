---
cairn: change
id: standard-onboarding-one-password
status: active
created: 2026-10-09
---

# Standard onboarding: an address, the domains, one password

## Why

The standard setup is meant for the user who wants their mail now. Today, for an address on a plain IMAP, CardDAV and CalDAV host, it costs about ten taps and the same password typed three times:

1. The address, then the arrow FAB.
2. A blocking dialog asking Recommended or Advanced (`setup_choice_title`), the very question the standard path exists to spare.
3. The domain screen, its three switches starting off (`showSetup`), plus a *send mail* switch.
4. One credential dialog per domain: `planAuthSteps` makes one step per password, even when the login and the password are the same ("Mail · Step 1/3", "Contacts · Step 2/3", "Calendar · Step 3/3"), each asking for the login again.
5. The modal first sync.

The only facts the app needs from this user are the address, which domains they want, and, when the chosen sign-in is a password, that password. Most accounts use one password for all three; different passwords are the advanced setup's business.

## What

- **No setup-mode question.** The standard setup is the default path. The advanced setup is a text link on the address screen ("Advanced setup") and under the password field ("Different passwords? Advanced setup").
- **Address screen.** One labelled email field, a full-width *Continue* button that carries discovery's loading state (the dialog used to hide that wait), the advanced link. No app bar on first run.
- **Domain screen, one page.** The address as context, the title "What do you want on this phone?", one card with a row per discovered domain, each ticked by default. Undiscovered domains are not shown in the standard setup.
- **One password.** When every ticked domain resolved to a password sign-in, one password field on the same screen, the login being the address, with a show/hide toggle and the line "Used for everything you picked above." One *Connect* button.
- **Browser grants.** When the ticked domains resolve to OAuth, no field: a *Continue in browser* button and the line "Your provider signs you in on its own page. You approve once, for everything you picked." (`planAuthSteps` already pools grants by authorization server and audience.)
- **Mixed sign-ins** (a password domain and an OAuth domain): the password field and the browser hop both run, password first. Rare; kept simple.
- **Sending** is connected with mail when discovery found an endpoint, with no switch. When none was found, nothing is asked; the account settings row already lets one be added later.
- **Per-domain result.** The one password is tried against each password domain. If all accept, the flow ends. If every one refuses, the field shows "Wrong password" in place. If some refuse, a result screen "Almost there" lists each domain (Connected, or "Password not accepted"), with *Continue without calendar* (connects what worked) and *Set up calendar in advanced setup*.
- **Landing.** Straight onto the mail list. The quiet, inbox-first sync is the `quiet-first-sync` change; this change only stops adding prompts in front of it.

## Designs

`designs/` holds the five frames from the design canvas, one self-contained `.dc.html` each (390×844, the app's own tokens: page `?colorBackground`, cards and fields on `@color/surface`, 22dp card radius, accent from the theme):

| File | Frame |
|------|-------|
| `NewWelcome.dc.html` | Address screen |
| `NewConnect.dc.html` | Domains and one password |
| `NewConnectBrowser.dc.html` | Same screen, browser sign-in variant |
| `NewPartial.dc.html` | One domain refused the password |
| `NewArrive.dc.html` | Landing on the mail list while the inbox fills (see `quiet-first-sync`) |

The files are HTML mockups for reading (structure, copy, sizes, colours), not code to port. Build the screens as the app builds every other screen: views and drawables, theme attributes, `@color/surface`, the existing dimens.

## Out of scope

- The advanced setup, untouched apart from being reached by link instead of the dialog.
- The first sync itself (`quiet-first-sync`).
