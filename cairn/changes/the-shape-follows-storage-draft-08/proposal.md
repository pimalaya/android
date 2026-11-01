---
cairn: change
id: the-shape-follows-storage-draft-08
status: draft
created: 2026-11-01
---

# The app's shape follows storage-draft-08, skipping draft-07

## Why

io-pimdir bff5737 (pushed, master, still 0.8.0) follows pimdir d671bbd: storage-draft-08, sync-draft-07, search-draft-08. cairn/changes/platform-sqlite step 2 writes the app's shape "at draft-07" and moves the pin "to the io-pimdir release following draft-07"; that release never shipped on its own, and since no test-build store converts (step 2 blocks start-up on another shape), writing the shape straight at draft-08 costs nothing that draft-07 would have saved. This change retargets step 2 and lists what draft-08 adds to it; platform-sqlite stays the plan, and io-pimdir-draft-05's rules stay folded into it.

Built on 2026-11-01 (rust/, a copy with `io-pimdir = { path = "../io-pimdir" }` at bff5737, `cargo check --all-targets`), 12 errors, nearly all draft-05 and draft-07 items platform-sqlite already owes:

- rust/src/offline/wire.rs:421, 609, 707, 887: `PimdirChangeKind::SetFlags { handle, add, remove }`, `PimdirWriteOp::Overrule`, `PimdirMutation::Edit { if_match, overrule }`, `PimdirRemoteSnapshot { narrowed, enumerated }`.
- rust/src/offline.rs:23, 131, 249, 303: `PimdirLoadSelection` (`WantsLoad { selection }`), `PimdirYield::WantsVerify`, `mutate` answering `PimdirMutateReport`.
- rust/src/offline/summary.rs:142: `PimdirTaskSummary { created, contacts }`.
- rust/src/mail/read.rs:12: `hash::PimdirHashAlgo` gone.

## What draft-08 adds to the shape, owed by the Java owner

The compiler sees none of these, since the owner is `PimdirDb` and `PimdirStorage`:

- **Identity and versions** (STORAGE §4, §6): `store_meta.format_version` `storage-draft-08` with `shape` `pimalaya-android`, `store_id`, `incarnation`, `derivation` 3, `sync_version` `sync-draft-07` written by the first sync write and checked.
- **The blob store** (§5, §7): BLAKE3 alone, one shard level `objects/<hash[0:2]>/<hash>`, a name checked (52 characters of `a-z2-7`) before any path, the no-replace write (link, then unlink the temporary), no link followed, quarantine/ for a body failing its name, verification before a body is served, parsed, pushed or copied; the drain reads `queue.object_hash` and parks a payload whose `object` differs.
- **Strict payloads** (§15.3, Annex B.2, spec/payloads/): every row Java enqueues matches its schema (a `submit` naming no `object`, `source` required, flags valid per §13); the drain parks a malformed row whole.
- **Reads re-signed** (keyset-reads-seek-their-bound): `_by_link` batch reads, `list_address_placements` paged, `count_mail_by_day` in minutes, `seq_for_link_any` with `:collection`, `list_unstamped_bindings` with `:narrowed` and `:enumerated` answering `LastPage`.
- **Sync answers**: `WantsVerify` for a sync and an upgrade, `PimdirUpgradeOptions` from the sync options, `Overrule`'s retained `dup:` bodies, `Written`.
- **Connectors**: IMAP, JMAP, Graph and Gmail state `narrowed` and `enumerated` on a round the download window (cairn/changes/mail-download-window) narrows: IMAP `UID SEARCH ALL`, JMAP `Email/query` by mailbox alone, Graph the folder's ids, Gmail the label's ids. Flag pushes as deltas.
- **`calendar.component.vtodo`** in place of `x-vtodo`.
- **The index at search-draft-08** (step 4): `index_meta` version, shape and floating zone, the `missing` retry, the thread prune, occurrence cuts, the query limits.

## Order

Step 2 splits in two, so a failure on the device points at the rules or at the SQL, not both (platform-sqlite's own reason for keeping steps 2 and 3 apart):

- **2a. Draft-08 on requery, canonical text.** The pin moves to bff5737 and the app keeps serving io-pimdir's canonical draft-08 text on requery, whose SQLite runs it; the Java owner and the bridge take every rule above. Start-up is blocked on a store of another draft (reinstall), as step 2 already says for another shape.
- **2b. The fork.** The `pimalaya-android` shape: the draft-08 text rewritten for SQLite 3.18 (rust/pimdir/), the guard green over it, the Java hand-written SQL moved in. Only SQL moves; the rules are 2a's.

Then platform-sqlite steps 3 to 5. Started beside the four CLI ports (2026-11-01) rather than after them, the app being the long pole; a fix the CLIs find in io-pimdir moves the pin again, nothing more. Device checks per step, as platform-sqlite says.
