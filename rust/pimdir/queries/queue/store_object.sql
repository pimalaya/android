-- Fork of STORE_OBJECT (sha256 f8da62829077acb5) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- The bytes are in the blob file already, written by this batch or streamed
-- there beforehand; the refcount is settled later in the batch (§14).
UPDATE objects SET size = :size WHERE hash = :hash;
INSERT INTO objects(hash, size, refcount)
SELECT :hash, :size, 0
WHERE NOT EXISTS (SELECT 1 FROM objects WHERE hash = :hash);
