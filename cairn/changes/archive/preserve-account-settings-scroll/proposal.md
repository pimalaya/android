---
cairn: change
id: preserve-account-settings-scroll
status: landed
created: 2026-11-17
---

# Preserve account settings position after signing in again

## Why

Returning from Sign in again currently calls AccountSettings.open(), which unconditionally scrolls the existing settings view to the top and hides the account title in the toolbar. The user loses the position of the action they selected.

## What

Distinguish a normal opening of account settings from returning to those settings after the account's onboarding flow. Keep the existing ScrollView and retain its scroll position and corresponding toolbar title state on return. Continue to refresh the displayed account settings, and keep normal openings at the top.

Cover leaving the flow with both toolbar Back and system Back through their shared return path. Keep the previous Sign in again reopening fix and unrelated work unchanged. Do not introduce RecyclerView, new dependencies, persistent navigation state or a settings-screen redesign.

Add a regression check using the existing Robolectric test style, update the onboarding specification and CHANGELOG.md, and record validation honestly. Unit tests remain unexecuted unless the user authorizes them; a native-backed test run requires the Nix environment, which is unavailable in this Windows shell.
