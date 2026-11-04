---
cairn: tasks
change: one-onboarding-path
---

# Tasks

- [x] Review the proposal; settle the open points
- [x] Fold advanced-onboarding-pages, standard-onboarding-one-password and onboarding-options-ask-on-switch into the spec as they stand, log and archive them
- [x] Step 1: drop the setup mode question and its links; the domain screen is checkbox rows alone, its one-password field and options gone
- [x] Step 1: Continue leads to the sign-in page for everyone (`runSignIns`)
- [ ] Step 1: device check
- [x] Step 2: the sentence wording per method and provider (`SetupPlan.serverName`, `provider`), no protocol, host or port
- [ ] Step 2: device check of the per-server cards (Posteo, Fastmail)
- [ ] Step 2: each sign-in card names its domains and says its sentence
- [x] Step 2: one password or token card per server, the "same login and password" switch (manual, off to begin with), sending as its own card checked over SMTP, a second credential stored when it differs
- [x] Step 2: the tune menu (`showTuneMenu`): each domain's other options, a pick moving it (`moveDomain`, `fallBack` on it too), *Custom settings* as a form in the card (`addCustomForm`), *Use discovered settings* back; sending's menu with the other servers and *Do not send*
- [ ] Step 2: device check of the tune menu and custom form
- [ ] Step 2: a ticked domain discovery found nothing for starts as the form; the nothing-connectable dialog goes
- [ ] Step 2: device check
- [x] Step 3, first piece: mail's mailboxes on the books page in a mailbox mode (`openMailboxSelection`), skipped for a single mailbox, applied at the first roster (`MailStore.chooseMailboxes`, MailFilterDefaultsTest)
- [ ] Step 3, first piece: device check
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
