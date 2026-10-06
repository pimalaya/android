---
cairn: log
change: drawer-domains
landed: 2026-10-06
---

# The domain buttons move into the drawer

The three domain buttons crowded the app bar beside each domain's own actions. They now open the drawer as rows, an icon and the domain's name like the footer's actions, under its title bar, whose closing cross now sits beside the app's name rather than Accounts, the selected one on an accent pill (`domain_selected`, edge to edge), followed by a delimiter and the account rows and footer as before. Pressing one closes the drawer and swaps the screen in place: the old left or right slide followed the bar's button order, which a vertical list no longer shows. A list screen's bar takes the burger and the domain's name instead (`showDomainTitle`), which contacts search and selection hide and restore as they did the buttons.

Capabilities moved: offline-store (new requirement: The drawer switches domains).

**The overflow goes.** With the domain buttons out of the bar there is room again, so `bar_more` and its menu are replaced by the buttons they hid: `contacts_birthdays`, `contacts_duplicates`, `contacts_transfer` (import and export, one popup of the two) and `bar_filter`, last on every list so it sits at the same spot across domains. The overflow's Accounts entry is dropped, the burger opening the same drawer. `ic_filter_list` is back; `ic_more_vert` and the `more` string went unused and were deleted.

**The drawer's divider.** The line under the domain rows takes `@color/divider_light` (a lighter tone than `divider`): in the surface tone it vanished against the surface block above it in the light theme.

**The drawer's spacing.** The drawer follows the M3 navigation drawer: the domain pill is inset 12dp from the drawer's edges with 16dp inside it, and the account and footer rows pad 28dp, so every icon sits on one column (`drawer_item_inset`, `drawer_item_inner`, `drawer_item_padding`). The footer's delimiter takes `@color/divider_light` too.
