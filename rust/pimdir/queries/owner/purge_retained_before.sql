-- Fork of PURGE_RETAINED_BEFORE (sha256 40ef7bcf7dc12a2f) for SQLite 3.18:
-- `RETURNING` (3.35) as a read of the rows, then their delete, in one
-- transaction.
--
-- The time-based sweep, store-wide since how long to keep is the owner's policy
-- rather than a collection's. The cutoff, :before (RFC 3339, written with its
-- milliseconds as the stamps are, §11.2), is the caller's parameter,
-- not the store's clock, so the boundary is deterministic even though the stamp
-- is SQLite's. A seek below the cutoff on items_by_retained, so a sweep reads
-- what it purges. It returns pinned hashes on purge_item's terms.
SELECT (SELECT o.hash FROM objects o WHERE o.id = items.object) AS object_hash,
       (SELECT o.hash FROM objects o WHERE o.id = items.conflict_object) AS conflict_object FROM items
WHERE retained_at IS NOT NULL AND retained_at < :before;
DELETE FROM items WHERE retained_at IS NOT NULL AND retained_at < :before;
