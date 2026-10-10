---
cairn: change
id: filter-page-account-hides
status: active
created: 2026-10-10
---

# Filter page: an unticked account folds its collections away

## Why

Device feedback on the filter page (2026-10-10):

- Rows flash yellow on press and focus: they draw the legacy `list_selector_background` instead of the theme's ripple every other page uses.
- Unticking an account unticks every collection under it. The choices are kept underneath (`MergedFilter` holds hidden accounts apart from hidden collections), but the page cannot tell an unticked account from a set of unticked collections, and ticking one of those collections quietly brings the account back.

## What

- Rows take `?android:attr/selectableItemBackground`, as `Sections` and the settings rows do. No other page uses the legacy drawable.
- Unticking an account hides its collections from the page, and the lists leave the account out. Each collection keeps its own box; ticking the account again lists them with their boxes as they were, all unticked ones included.
- An account's box reads unticked when the account is hidden, and only then. Shown, it reads ticked when every collection is, partly ticked otherwise, none ticked included: a blank box always means folded away.
- A collection's box reads its own choice. Ticking a collection no longer needs to bring its account back, since a hidden account lists none.
- The stored shape is unchanged (hidden accounts, hidden collections per domain), so nothing migrates. A store where every collection of a shown account is hidden now reads partly ticked, its collections listed unticked.
