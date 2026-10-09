---
cairn: tasks
change: account-settings-page
---

# Tasks

- [x] Review the proposal and the designs; settle the open points below
- [x] Rebuild `renderAccountSettings` on `Sections`: identity block, Use this account, Mail, Addressbooks, Servers
- [x] Identity block: disc, address, domains line, sync pill (reuse the drawer card's pieces)
- [x] Bar: round close button; the address as the bar's title once the identity scrolls away
- [x] Mail rows as title and value; single-choice dialogs for sync period and download policy, applied on tap
- [x] Value wording for the sync period ("The last 3 months") and the policies, English and French
- [x] Addressbooks card: a switch per book, two checkbox rows under a book that is on; drop the advanced fold (`account_advanced`)
- [x] Servers card, read only, one row per server
- [x] Remove button at the bottom in the error colour, keeping the confirmation; drop `account_delete` from the bar
- [x] Apply every change at once; drop the FAB (`account_fab`), `BookSettings` staging and `save`
- [x] "Your name" row, sent as the `From` display name
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`
- [ ] Device test
- [ ] Fold the delta into `spec/offline-store.md` and log

## Open points

- Switching an addressbook off: no confirmation, it only flips the book's flags and nothing is lost.
- Switching the whole account off: no confirmation, its data stays.
