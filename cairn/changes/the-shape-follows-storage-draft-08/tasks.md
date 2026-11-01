---
cairn: tasks
change: the-shape-follows-storage-draft-08
---

# Tasks

- [ ] Review with the user: platform-sqlite step 2 retargeted from draft-07 to draft-08 (bff5737), its proposal and tasks amended
- [x] 2a. Bridge compile port (12 errors), `cargo test` green, the std-store dev tests on bff5737
  - 2026-11-01: 547 Rust tests pass; `served_sql_holds_to_the_sqlite_floor` stays red as platform-sqlite step 1 left it, now 69 statements failing where 186 did (migration 0001 loads on 3.18.2), the rest 2b's to clear. io-pimdir's `[patch]` entries are not replicated: its ical-rs patch serves its `search` feature, which neither the bridge nor the dev-dependency enables, and pimalaya-cli its `cli`.
  - io-pimdir's payloads.json runs through the bridge (offline/store.rs), all 34 cases.
- [ ] 2a. Java owner on the canonical draft-08 text: identity and versions, blob store, strict payloads, re-signed reads, sync answers, start-up blocked on another draft; JVM tests failing first
  - Done 2026-11-01: identity and versions (INIT_STORE_META at draft-08, store id and incarnation by the canonical text, derivation 3, `sync_version` written by the first engine write and another refused, `synchronous = FULL`, `secure_delete`, `trusted_schema = OFF`, `auto_vacuum` before creation; reconciliation and backfills deleted); the blob store (BLAKE3 through the bridge, `Native.pimdirHash` and `pimdirHashFile`, objects.json run by PimdirHashTest; one shard level, the name checked before a path, the no-replace write by hard link, no link followed, quarantine/, a body verified before it is served, linked by a lookup or pushed); flags and lists as rows (`placement_flag`, `binding_base_flag`, `mail_in_reply_to`, `task_category`) through the canonical `retain_*`/`add_*`/`replace_*`/`insert_*`; every list parameter bound through `temp.param` on one pinned connection (PimdirDb.read, query, changed); re-signed reads (`_by_link`, `seq_for_link_any` with `:collection`, `count_mail_by_day` in minutes, the keyset statements seeking their bound, so MailStore's own CTE went, `list_unstamped_bindings` with `:narrowed` and `:enumerated` on a `lastPage` load alone); sync answers (`verify`, `Written` with the overruled bodies, the `overrule` op keeping `dup:` retained items and refusing `conflict-unread`, PimdirUpgradeOptions from the sync's options); the start-up block (PimdirDb.refusal over facts read read-only, the verdict in Rust, StoreRefusedActivity).
  - Left: strict payloads (the app's own `submit` payload is not Annex B.2's: no `source` nor `rcpts`, `messageId` and `sortKey` it should not carry; `Native.pimdirAction` reads a row strictly but neither the enqueue nor the drain calls it yet); `bindings.shared_object` never written by PimdirStorage (STORAGE §5, SYNC §9); the cross-source merge's item-level conflict and `Written.conflicted` (PimdirStorage keeps its own rules); a parked round (`SyncReport.parked`) surfaced with a way to accept the absences; `list_address_placements` is not used by Java.
- [ ] 2a. Connectors: `narrowed` and `enumerated`, flag deltas
  - Done 2026-11-01: flag pushes as the engine's two deltas on IMAP, JMAP, Graph and Gmail (MailEngine.pushFlags, one marker write each); `narrowed` on an IMAP round under `SENTSINCE`/`SENTBEFORE`, a JMAP round under `after`/`before`, every Graph band page and a Gmail round under `after:`; `enumerated` on IMAP's last page (`UID SEARCH ALL`); `esc:` stripped from the name a CalDAV or CardDAV create is pushed under (PimdirStorage.statedOf).
  - Left: `enumerated` on JMAP, Graph and Gmail, each costing a request per page of the whole mailbox (SHOULD).
- [ ] 2a. Pin to io-pimdir bff5737 by git rev; `:app:assembleDebug :app:testDebugUnitTest :app:assembleRelease` green; installed, device check by the user
  - Pin moved and the bar green on 2026-11-01; device check owed.
- [ ] 2b. rust/pimdir/ shape at storage-draft-08 for SQLite 3.18, the 3.18.2 guard green over it, Java's hand-written SQL moved in; device check
- [ ] Then platform-sqlite steps 3 to 5, the index at search-draft-08
