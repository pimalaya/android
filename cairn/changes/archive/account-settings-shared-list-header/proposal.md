---
cairn: change
id: account-settings-shared-list-header
status: landed
created: 2026-11-17
---

# Use the shared list header in account settings

## Why

Account settings currently rebuild a ScrollView and independently animate the toolbar title. The recent return fix adds an account marker solely to suppress that screen's forced scroll reset. The user requests the same title handoff as Emails and simpler return handling.

Investigation confirmed that Emails uses ListView and ListHeader, not RecyclerView or a platform collapsing toolbar. The human explicitly chose reusing that existing ListView + ListHeader mechanism rather than adding RecyclerView support.

## What

- Replace the account settings ScrollView with ListView and reuse ListHeader's existing scroll-to-toolbar title behavior. The address becomes the shared large title; retain the avatar, covered domains and sync status below it without repeating the address.
- Keep the existing Sections controls and grouping. Present the account, domains and sign-in cards through a small local native adapter, with the removal controls as the list footer. The existing SectionsAdapter is for flattened homogeneous data rows and would require an unnecessary new settings-row model here.
- Route the shared title callback to the account overlay's toolbar while that overlay is active. Keep the underlying domain's title, list position and FAB behavior unchanged.
- Replace the signInAgainEmail marker with an explicit return entry point that refreshes the displayed account without recreating the list or resetting its position. Normal openings and switching accounts still start at the top.
- Preserve all setting actions, permissions, subscription selectors, credentials, sync guards and deletion confirmation. Read deletion text from the account data rather than relying on a footer being attached to the view tree.
- Update the existing Robolectric regression for the native list position and shared title handoff, including fresh opening/reset behavior. Fold the specification, update CHANGELOG.md and write the landing log.

No new dependencies, RecyclerView, navigation framework, persistent scroll snapshot, settings redesign or row-model abstraction. Preserve unrelated work and the previous Sign in again reopening fix.

## Validation

Run focused IDE analysis and whitespace/Cairn checks. With human approval, build the Windows debug APK and compile unit-test sources with a limit of up to five minutes per build/check command. Do not execute unit tests or operate a device; the earlier test restriction remains, and native-backed unit execution requires the unavailable Nix environment. Report runtime and visual validation as unverified.
