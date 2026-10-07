---
cairn: log
change: pages-walk-the-global-order
landed: 2026-10-07
---

# Mail pages walk the global order

Capabilities moved: none (offline-store's reconcile already covers an index the schema adds).

A page of the merged mail list over several mailboxes read every item of the set and sorted it in a temporary B-tree before returning its fifty rows. io-pimdir be03184 (pimdir 22f1f2c) declares `items_by_sort_global` on `(sort_key, seq, collection) WHERE deleted = 0` and writes the collection test of `list_mail_page_filtered` and `search_mail` as `+i.collection IN ...`, so a planner without statistics walks that index rather than `items_by_seq`: on a 50k store, 15 ms per page down to 0.3 ms, no `ANALYZE` needed. The bridge moves to it, taken as a git dependency pinned by rev.

**Store.** A fresh store gets the index from the canonical DDL; an existing one gains it on open through the generic reconcile, which creates every index the schema declares and the store lacks, as it added `sources.round_band`. Both statements are the crate's new texts, crossing JNI as every statement does: nothing in Java changed.

**Tests.** `PimdirDbTest.aMergedPageWalksTheGlobalOrderOnAnOlderStore` drops the index, reopens the store, finds it back, and reads `EXPLAIN QUERY PLAN` of the canonical page statement over two mailboxes: it uses `items_by_sort_global` with no `TEMP B-TREE FOR ORDER BY`. The plan assertion holds on Robolectric's SQLite; a device's may differ in version, the index's presence being the part that does not depend on it.
