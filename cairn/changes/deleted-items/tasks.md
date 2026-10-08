---
cairn: tasks
change: deleted-items
---

# Tasks

- [x] Bridge: expose `list_retained`, `count_retained`, `retained_bytes`, `purge`, `purge_retained_before`, `collect_garbage` over JNI (check what is already wired). The app takes io-pimdir without `client`, so those methods do not exist here; their canonical statements already cross through `pimdirSql`, and `DeletedItems` runs them on io-pimdir's terms. Added `Native.pimdirDerive` (Annex A derivation of a stored body) for the restore's summary and identity.
- [x] `DeletedItems` page: rows across domains, newest first, paged per collection. Raised from a drawer row (the app has no Settings screen); rows walked per collection by keyset, shown 100 at a time.
- [x] Restore: collection picker (last one preselected, else the account's default), `mutateAdd` with the stored body; refused for a body-less mail, for a body that is not UTF-8, over a live item, and for mail on an account that appends nothing (Graph, Gmail, JMAP)
- [x] Rows still bound by a source shown as waiting, no actions
- [x] Free space: bytes shown, purge + collect with confirmation
- [x] Tests: restore into the last collection revives the row; restore elsewhere adds; a waiting row is untouched by the purge (DeletedItemsTest, plus a body-less mail refused and an opened mail revived under its handle)
- [ ] Land: spec (a new capability or settings section), log, CHANGELOG
