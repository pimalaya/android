---
cairn: tasks
change: deleted-items
---

# Tasks

- [ ] Bridge: expose `list_retained`, `count_retained`, `retained_bytes`, `purge`, `purge_retained_before`, `collect_garbage` over JNI (check what is already wired)
- [ ] `DeletedItems` page under Settings: rows across domains, newest first, paged per collection
- [ ] Restore: collection picker (last one preselected), `mutateAdd` with the stored body; refused for a body-less mail
- [ ] Rows still bound by a source shown as waiting, no actions
- [ ] Free space: bytes shown, purge + collect with confirmation
- [ ] Tests: restore into the last collection revives the row; restore elsewhere adds; a waiting row is untouched by the purge
- [ ] Land: spec (a new capability or settings section), log, CHANGELOG
