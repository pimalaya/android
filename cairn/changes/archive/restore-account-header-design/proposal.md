---
cairn: change
id: restore-account-header-design
status: landed
created: 2026-11-17
---

# Restore the original account header design

## Why

The ListView migration unintentionally changed the account identity layout, moving the avatar below a new large title. The user rejected that redesign and explicitly confirmed the intended behavior before implementation: keep the avatar in its original place in the scrolling account header, and show only the account name/address in the toolbar after scrolling.

## What

- Restore the original identity layout: the 52dp avatar on the left, the existing 22sp address and domain line on the right, and sync status below, with the original spacing and theme roles.
- Let ListHeader use the account identity layout instead of adding it below the generic domain header. Keep the existing constructor and default layout for Emails and the other domain lists.
- Calculate the shared title handoff in header coordinates so the nested address triggers it correctly. Reuse the platform descendant-rectangle conversion, not a separate account scroll listener.
- Keep the ListView adapter, native position preservation, toolbar routing, controls and deletion safeguards unchanged. Only the account text enters the toolbar; no avatar is added there.
- Extend the existing regression with identity placement and partial-scroll title visibility checks. Update the onboarding specification and CHANGELOG, and record the correction separately without rewriting previous logs.

## Validation

Use focused IDE analysis, whitespace and Cairn checks. Rebuild the debug APK and compile the existing unit-test sources within the previously approved build limit. Do not execute tests or operate a device. Report visual/runtime behavior as unverified.
