-- Fork of UPSERT_PLACEMENT (sha256 9a82f22162ed546f) for SQLite 3.18: the
-- upsert (3.24) as an update of the row by key, then its insert where none
-- is there, and the RETURNING (3.35) as a read by key, run in one
-- transaction.
UPDATE placement SET link_id = :link_id, hash = :hash
WHERE collection = :collection AND seq = :seq;
INSERT INTO placement(collection, seq, link_id, hash)
SELECT :collection, :seq, :link_id, :hash
WHERE NOT EXISTS (SELECT 1 FROM placement WHERE collection = :collection AND seq = :seq);
SELECT id FROM placement WHERE collection = :collection AND seq = :seq;
