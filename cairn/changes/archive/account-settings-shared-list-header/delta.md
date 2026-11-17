---
cairn: delta
change: account-settings-shared-list-header
---

## ADDED Requirements

### Requirement: Account settings use the shared list header
Account settings SHALL use ListView and the same ListHeader title handoff as the Emails list, rather than a separate account-specific scroll animation. The account address SHALL appear once as the large scrolling title and in the overlay toolbar only when that title has scrolled away. The avatar, covered domains and sync status SHALL remain visible in the scrolling header. Existing settings, permissions, deletion confirmation and sync safeguards SHALL remain available. Scrolling account settings SHALL NOT change the obscured domain list's toolbar title or FAB state.

#### Scenario: The account title enters and leaves the toolbar
- GIVEN account settings at the beginning of the list
- THEN the account address is the large header title and the toolbar title is hidden
- WHEN the large title scrolls out of view
- THEN the shared header mechanism shows the account address in the account toolbar
- WHEN the list scrolls back to its beginning
- THEN the account toolbar title hides again

## MODIFIED Requirements

### Requirement: Returning from sign-in retains the settings position
Returning from Sign in again SHALL refresh the displayed account without recreating the list or resetting its native scroll position, and the toolbar title SHALL reflect the shared header's state. Opening account settings normally or returning to a different account SHALL start at the top.

#### Scenario: Returning to the selected settings position
- GIVEN account settings scrolled to Sign in again
- WHEN the user selects it and returns from the flow
- THEN account settings retain their first visible list position and offset, within the refreshed content's bounds
- AND the toolbar title reflects the shared header's state
- WHEN the user leaves settings and opens them normally
- THEN the settings start at the top

## REMOVED Requirements

None.
