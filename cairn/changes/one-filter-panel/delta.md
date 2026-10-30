---
cairn: delta
change: one-filter-panel
---

Folds into `spec/offline-store.md` (the filter, the list header, what syncs), `spec/mail.md` (the sync skips, *Download*) and `spec/tasks.md` (the chips).

## ADDED Requirements

### Requirement: Every list header reads the same
Every list SHALL open on its large title, a line counting what it lists, its search field, its filter panel or the pills of what it holds on, then its rows. The search field SHALL end with the filter button, accented while any filter is on. The list's bar SHALL NOT carry a filter button. Task rows SHALL carry square checkboxes.

#### Scenario: The Tasks header
- GIVEN 12 next actions and no filter on
- WHEN the Tasks tab is shown
- THEN its header reads Tasks, 12 tasks, then the search field, with no chip row and no pill

### Requirement: One inline panel holds every filter of a list
The filter button SHALL unfold under the search field, inside the list's header and scrolling with it, a panel of one row per section naming the section and its current value, and fold it back. Tapping a section's row SHALL open its checklist under it and close any other: a radio list for a single choice, a checkbox list with square boxes for a multiple one, past 6 entries the first 6 with the ones on first and "Show all N". Every tick SHALL apply at once, the panel and the pills holding one state; the panel SHALL carry no Apply, Cancel or Reset, and the filter button SHALL open and fold it. In every section none ticked SHALL mean any, the value reading Any, and each choice ticked SHALL stand as its own pill; collections (calendars, mailboxes, books, folders) alike. Categories SHALL offer None first, for what carries no category. The Mail panel SHALL offer Show (All, Inbox, Sent, Drafts, Trash, Junk, Archive, All by default), Only (Unread, With attachments), Received (Any time, Before, On or After a date, on the `Date` header) and Mailboxes; the Tasks panel Categories (Inbox, Waiting, Someday, then every category any task carries, any number, a task passing when it carries any, compared folded) and Status (To do, In progress, Done, Cancelled, any number), Due (Any time, Before, On or After a date, on `DUE`, a task with none never passing) and Calendars (every calendar synced that takes to-dos); the Calendar and Contacts panels Show (All, Default) and their collections. Filters SHALL be kept per list across restarts; the search and the panel's unfolding SHALL NOT.

#### Scenario: A tick waits for Apply
- GIVEN the Tasks panel unfolded with Categories open
- WHEN Garden is ticked
- THEN the rows are unchanged until *Apply*, which narrows them to Garden tasks and folds the panel

### Requirement: The pills stand under the panel
Open or folded, the panel SHALL have under it one pill per choice ticked (each state, category, kind, collection, flag), the role picked, each date bound and the due day with its comparison. Tapping a pill SHALL remove that criterion alone and refilter at once. With no filter on there SHALL be no pill.

#### Scenario: Folding
- GIVEN Garden and Due before November 7 on
- WHEN the panel is folded
- THEN the pills Garden and Due before 7 Nov 2026 stand under the search field, and tapping Garden leaves Due alone on

### Requirement: What syncs is set in account settings
An account's settings SHALL list, per domain it covers, its collections with a switch each, and on each mailbox *Keep whole* and *Stop*. Every sync (a pull, the drawer's, a background run, the body download, the fill, *Load more*) SHALL take the collections switched on and SHALL NOT take one switched off, whatever a filter shows. Switching a collection on SHALL start its first sync as a newly shown one does today; switching it off SHALL keep its stored items. A new account SHALL switch on its inbox alone off Gmail, every label but Trash and Spam on Gmail; an account from before this rule SHALL start with the ticks its filter held.

#### Scenario: Hiding without stopping
- GIVEN Sent switched on in the account's settings
- WHEN the mail filter shows Inbox alone and the list is pulled
- THEN Sent is still synced

### Requirement: The Tasks list shows open, then done, then cancelled
With no Status on, the Tasks list SHALL list the open tasks first (to do and in progress), due ones by due date then undated ones, or oldest captured first when one GTD list alone is on; then the done ones, the last done first, their rows at the opacity of a mail row with no body; then the cancelled ones, the last captured first, dimmed alike and struck through. A Status on SHALL narrow the list to the tasks in it. The list SHALL NOT name a "Next" list.

#### Scenario: A done task
- GIVEN an open task and a task completed yesterday
- WHEN the Tasks tab loads with no filter on
- THEN the open task is listed first, and the done one under it, dimmed

## MODIFIED Requirements

### Requirement: A list's pull syncs what it shows
Pulling a list down SHALL sync that list's domain alone, every collection switched on of every account that is on, whatever the filter hides; the contacts pull SHALL also run the phone's pass. The drawer's sync SHALL take every domain.

### Requirement: The filter lists accounts and their collections
The filter panel's collections section SHALL list each account that is on and covers the domain with a checkbox, and below it its collections switched on in its settings, with theirs, the on-device book listed as an account of its own. It SHALL narrow the view alone. Unticking an account SHALL hide it and keep its collections' own choices; ticking it back SHALL restore them. Its default SHALL show every collection listed.

### Requirement: The Tasks chips are filters
Replaced by "One inline panel holds every filter of a list": the Tasks tab SHALL carry no chip row. Clarify and Weekly review SHALL stay buttons of the Tasks list's app bar, Weekly review accented while the review is due.

### Requirement: A role gives direct access across accounts
The role choice moves into the Mail panel's Show; its meaning is kept: the mailboxes holding that role of the accounts shown, and no sync by itself.

### Requirement: A list bar carries its actions inline
Every list screen SHALL carry its actions as bar buttons with no overflow: on contacts the birthdays, the duplicate remover and one import/export button opening a menu of the two. A list's search SHALL sit in its header, under the large title, not in the bar, the filter button at its end.

## REMOVED Requirements

### Requirement: Applying the filter syncs what it newly takes
Moved to "What syncs is set in account settings": the filter no longer syncs.

### Requirement: A sync skips what the filter hides
Replaced by "What syncs is set in account settings".
