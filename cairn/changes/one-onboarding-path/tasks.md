---
cairn: tasks
change: one-onboarding-path
---

# Tasks

- [x] Review the proposal; settle the open points
- [x] Fold advanced-onboarding-pages, standard-onboarding-one-password and onboarding-options-ask-on-switch into the spec as they stand, log and archive them
- [ ] Step 1: drop the setup mode question; one domain screen with checkbox rows, nothing ticked, the best-ranked option selected under each ticked domain as a sentence row
- [ ] Step 1: the sentence wording per method and provider (browser, password, API token), no protocol, host or port
- [ ] Step 1: device check
- [ ] Step 2: More unfolding the alternatives as radio rows in the card, folding back on a pick
- [ ] Step 2: one settings form (protocol, host, port, security, method), prefilled from the selected option, empty for a domain discovery found nothing for; it replaces manual entry
- [ ] Step 2: sending on mail's card: *Send mail* checkbox, its sentence row, More and the form
- [ ] Step 2: the advanced setup's sign-in page as the only sign-in step
- [ ] Step 2: device check
- [ ] Step 3: extract the per-domain section builders of `AccountSettings` so onboarding and settings render the same rows
- [ ] Step 3: one page per ticked domain (mail: notifications, download window; contacts and calendars: collections, phone mirror), Continue, Finish on the last
- [ ] Step 3: no collection selection for a single collection
- [ ] Step 3: the phone mirror and notifications as switches in onboarding, as in settings
- [ ] Step 3: device check
- [ ] Step 4: check `SyncCoordinator` runs one domain's first pass while another's runs, for one account
- [ ] Step 4: save the account after the sign-ins succeed, with a setup-pending mark until Finish
- [ ] Step 4: Continue on a domain page starts its first sync in the background, nothing shown
- [ ] Step 4: backing out removes the account and its store; an account still marked pending at launch is removed
- [ ] Step 4: device check
- [ ] Step 5: Finish lands on the first ticked domain in the bar's order, the dock showing what still runs
- [ ] Step 5: device check
- [ ] Remove the setup mode, its strings and the advanced domain step once nothing reads them
- [ ] Fold the delta into cairn/spec/onboarding.md, log, CHANGELOG, archive; reword the "advanced setup" left in the intro, in "An account can gain a sender after it is connected" and in "Setup accepts encrypted servers only"
