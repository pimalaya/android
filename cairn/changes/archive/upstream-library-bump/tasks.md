---
cairn: tasks
change: upstream-library-bump
---

- [x] Bump every dependency to its released version and drop the git patches
- [x] Follow the vcard-rs and ical-rs marker move to the decoded model
- [x] Follow `VcardMerge`, `CarddavCardEnumOk` and `WebdavSyncCollectionOptions`
- [x] Enumerate through the `PROPFIND` fallback, and retire the app's own
- [x] Remove the ambiguity freeze and refuse the repointing write
- [x] Carry `conflictObject` on the placement wire, both directions
- [x] Store the diverging body on `bindings.conflict_object`, refcounted
- [x] Hydrate a conflict holding no diverging body
- [x] Rebase a resolution onto the whole observed state, revision and body
- [x] Reconcile the store's shape from the canonical DDL
- [x] Rust and Java suites green
