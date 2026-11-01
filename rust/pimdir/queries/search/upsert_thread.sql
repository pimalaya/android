-- Fork of UPSERT_THREAD (sha256 6d213e79013830b9) for SQLite 3.18: the
-- upsert (3.24) as an update of the row by key, then its insert where none
-- is there, run in one transaction.
UPDATE thread SET first = :first, last = :last, count = :count WHERE id = :id;
INSERT INTO thread(id, first, last, count)
SELECT :id, :first, :last, :count
WHERE NOT EXISTS (SELECT 1 FROM thread WHERE id = :id);
