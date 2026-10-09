---
cairn: tasks
change: advanced-onboarding-pages
---

# Tasks

- [x] Review the proposal and the designs; settle the open points below
- [x] Domain step, advanced: one card per domain (glyph tile, name, switch), two-line radio rows with hairlines, manual entry last
- [x] Mail card: SENDING sub-header and its rows, same visibility rules (`sends`, `offers`)
- [x] `planAuthSteps`: group password steps by server and login, keep OAuth pooling
- [x] New sign-in step (a panel in `auth_flipper`) with one card per planned step: password, token, manual entry, browser
- [x] ~~Fields with their name in an 84dp column inside the pill~~ (dropped on review: plain placeholders, `PillField`)
- [x] "Same login and password as <domain>" checkbox from the second password card on
- [x] Manual sending server as a field in Mail's card; drop `promptManualSubmission`'s dialog
- [x] Connect runs every card; per-card result, refused cards in error under their fields, the rest kept
- [x] Browser card: "Sign in with browser", then "Signed in"; "Use my own OAuth client" link
- [x] OAuth client page replacing the dialog (placeholders, not names above the value)
- [x] Addressbooks step: one card of checkbox rows, "Finish"
- [x] Remove the sign-in `AlertDialog`s and the strings only they used (`setup_step_title` and its kin) once nothing reads them
- [x] Strings, English and French
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`
- [ ] Device test
- [ ] Fold the delta and log

## Open points (settled)

- The "same as" checkbox: off by default.
- Browser grants: a card's own button runs its grant at once, and Connect runs every browser card not signed in yet, one after the other, once the typed cards have passed.
- A refused card: the user stays on the page to correct it and taps Connect again; cards already signed in are not tried again. No "continue without": switching the domain off on the previous page does that.

## Notes

- The fields carry their name as a placeholder only (review of 2026-10-09): a prefilled field (the login, the OAuth endpoints) shows its value with nothing naming it.
- The standard setup uses the same page for what its one password cannot cover (an API token, a password option without a login); a standard setup that only needs browser grants still goes straight to the browser.
- Every credential dialog is gone: manual server, password, token, manual sending server, OAuth client.
- A card is checked the way the first sync signs in (the probes of `standard-onboarding-one-password`), its domains side by side.
