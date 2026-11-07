---
cairn: log
change: add-button-waits-for-first-sync
date: 2026-11-07
---

# The add button waits for a new account's first sync

Seen by the owner on the production build (2026-11-07): right after onboarding, the mail tab showed *New message* for a fraction of a second, then shrank it away as the first sync's dock appeared.

- Cause: the account lands on its tab with the button grown (MainActivity.listFab), and the tab's first sync starts a moment later (posted from applyChrome), its dock only then holding the button away.
- SyncCoordinator.firstSyncAhead: a tab whose first sync an account that is on still owes, none having run since the app opened or the account connected, whatever it ended with. MainActivity.fabAway counts it with the dock, so the button stays away from the moment the tab shows; a failed first sync frees it like a finished one, never holding it for good.
- The button a sync held comes back only after 400 ms with nothing holding it again (refreshFab), so a dock following at once, the fill after the first mail sync or a first sync after a pass, does not flash it; the filters keep their immediate return. MainActivity.syncing refreshes it too, for a sync whose dock never showed.
- Regression: FirstSyncAheadTest, failing with the old rule (nothing ahead held the button) at its first assertion. `:app:assembleDebug` and `:app:testDebugUnitTest` green. Not seen on a device yet; the 400 ms grace covers a fill dock appearing within it.

Capabilities moved: offline-store (the dock requirement's add-button sentence, modified, and the scenario "Landing on a new account's mail", added).
