-- Fork of BUMP_GENERATION (sha256 1d04a3ab4e1d5148) for SQLite 3.18:
-- `RETURNING` (3.35) as the update, then a read of the row it moved, in
-- one transaction.
--
-- Run in the same transaction as the handle-space rebuild it records (§12).
UPDATE collections SET generation = generation + 1 WHERE id = :collection;
SELECT generation FROM collections WHERE id = :collection;
