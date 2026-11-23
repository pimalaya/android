-- Fork of PURGE_ITEM (sha256 2e403bfbbcb1ed1b) for SQLite 3.18:
-- `RETURNING` (3.35) as a read of the row, then its delete, in one
-- transaction.
--
-- The only true delete, guarded on retained_at so it never takes a live item.
-- It returns the pinned hashes for release_pins in the same transaction, and
-- the delete trigger counts the purge (§4.5, §14).
SELECT (SELECT o.hash FROM objects o WHERE o.id = items.object) AS object_hash,
       (SELECT o.hash FROM objects o WHERE o.id = items.conflict_object) AS conflict_object FROM items
WHERE collection = :collection AND seq = :seq AND retained_at IS NOT NULL;
DELETE FROM items
WHERE collection = :collection AND seq = :seq AND retained_at IS NOT NULL;
