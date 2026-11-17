---
cairn: delta
change: restore-account-header-design
---

## ADDED Requirements

None.

## MODIFIED Requirements

### Requirement: Account settings use the shared list header
Account settings SHALL use ListView and the same ListHeader title handoff as the Emails list, rather than a separate account-specific scroll animation. Reusing that mechanism SHALL preserve the original account identity design: the avatar on the left, the account address and covered domains on its right, and sync status below, with the original typography and spacing. Only the account address text SHALL appear in the overlay toolbar when its scrolling title has left view; the avatar SHALL remain solely in the scrolling identity block. Existing settings, permissions, deletion confirmation and sync safeguards SHALL remain available. Scrolling account settings SHALL NOT change the obscured domain list's toolbar title or FAB state.

#### Scenario: The account title enters and leaves the toolbar
- GIVEN account settings at the beginning of the list
- THEN the original avatar, address, domains and status arrangement is shown and the toolbar title is hidden
- WHEN the nested address has not yet fully scrolled out of view
- THEN the toolbar title remains hidden
- WHEN the address scrolls out of view
- THEN the shared header mechanism shows only the account address text in the account toolbar, without an avatar
- WHEN the list scrolls back to its beginning
- THEN the account toolbar title hides again and the original identity layout is shown

## REMOVED Requirements

None.
