---
cairn: log
change: deleted-items
landed: 2026-10-08
---

# Deleted items: one list to restore or free space

Commit f2e59a4, plus the restore of non-UTF-8 bodies landed with this entry.

Capabilities moved: offline-store (added: deleted items can be restored or purged).

**Reads.** The app takes io-pimdir without `client`, so `list_retained`, `retained_bytes`, `purge_retained_before` and `collect_garbage` run through their canonical statements (`PimdirSql`) in `DeletedItems`, one collection a keyset page at a time. `Native.pimdirDerive` (Annex A) gives a restored body its summary and, for mail, its link id.

**Restore.** A `mutateAdd` of the stored body: home it revives the retained row and its public id, elsewhere it adds a placement. The add now carries bytes as `bodyBase64`, as `storeObject` does since mail-offline-policy, so a mail in an 8-bit charset is restored byte for byte (the UTF-8 refusal and its string are gone).

**What the implementation corrected in the delta.**
- A drawer row opens the page: the app has no Settings screen.
- Mail restores on IMAP only, its push appending a create with no origin; Graph, Gmail and JMAP refuse it ("not supported for this account yet"). A restored message takes a new UID once uploaded, its link id being the handle.
- Rows are walked per collection and sorted in memory, waiting rows first: no store-wide newest-first read was added to io-pimdir. Shown 100 at a time.
- The picker offers the account's writable collections of the kind, the last one preselected, else the account's default; a restore over a live item is refused.
- The purge collects unreferenced bodies but walks no orphan blobs: the app's writers take no io-pimdir staging lock.

**Left open.** Automatic purge on a schedule; mail restore on Graph, Gmail and JMAP, which needs an upload path for a create with no origin.
