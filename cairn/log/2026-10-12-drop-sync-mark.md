---
cairn: log
change: drop-sync-mark
landed: 2026-10-12
---

# Drop the per-row sync mark

Capabilities moved: offline-store (modified: a local action is visible before any sync, no longer carrying a pending or refused mark), mail (modified: a message is deleted into the account's trash, a sent message is in Sent at once, a push converges on a message gone, none of them marking the row).

The cloud mark a message, contact or agenda row carried while its change waited for a push, red once the server refused it for good, confused more than it told, and is gone with its icon and strings, as is the *Refused by the server* line a refused message row took. What only fed it went too: `MailStore.row` no longer reads the store once per row to work out whether the message owes a push (`PimdirStorage.unsynced` removed), the contact and event lists no longer read the binding columns for it, and `Refusals`, the app's record of refused changes kept beside the store, is deleted with the engines' `refuse`/`forgetRefusals`: a refused change is rejected on the wire as before. The outbox row's own states, the deleted-in-place line, the conflict mark and the dim of a message not fully downloaded are unchanged. The e2e suite reads pending and pushed off the store itself (`E2eDevice.owes`).
