---
cairn: tasks
change: standard-onboarding-one-password
---

# Tasks

- [x] Review the proposal and the designs; settle the open points below
- [x] Address screen: labelled email field, full-width *Continue* with discovery's loading state, *Advanced setup* link; drop the app bar on first run
- [x] Drop the setup-choice dialog (`setup_choice_title`, `chooseSetup`); the standard setup is the default, the advanced one entered from the links
- [x] Domain screen (standard): one card of discovered domains, each ticked by default (`showSetup` starts them off today); undiscovered domains hidden
- [x] One password field on the domain screen when every ticked domain signs in with a password; login is the address
- [x] `planAuthSteps`: merge password steps sharing a login into one step covering their domains; keep OAuth pooling as is (done as: the shared password signs its domains in before planning, and the plan skips any domain already holding a credential)
- [x] Try the one password against each password domain; collect per-domain results instead of stopping at the first failure
- [x] Result screen when some domains refuse: Connected or "Password not accepted" per domain, *Continue without <domain>*, *Set up <domain> in advanced setup*
- [x] All refused: inline "Wrong password" on the field, nothing saved
- [x] Browser variant: *Continue in browser* instead of the field when the ticked domains resolve to OAuth
- [x] Sending (standard): connect the discovered endpoint with mail, no switch
- [x] Strings, English and French
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`
- [ ] Device test
- [ ] Fold the delta and log

## Open points (settled)

- Mixed password and OAuth domains: one screen running both, the password checked first, then the browser hop.
- Login: each domain's discovered login, the address when discovery names none. A password option with no login at all (a bare domain typed) keeps its own prompt.
- The *send mail* switch: dropped.

## Notes

- The check signs in the way the first sync will: the mail session opens and authenticates, the address books and calendars list. The probes run side by side.
- "Password not accepted" needs the refusal told apart from a dead network: a 401 or 403. The IMAP bridge now reports a refused LOGIN/PLAIN as 401, as it already did for XOAUTH2; any other failure reads "Could not connect".
- Back from the result step, or a cancelled browser grant, drops what was signed in and returns to the domain screen.
- Reaching the advanced setup from the standard screen keeps the ticked domains and the configuration picked for each.
