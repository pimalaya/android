-- Fork of UPSERT_CHECKPOINT (sha256 3730c1ae1e7b0794) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE sources SET checkpoint = :checkpoint
WHERE collection = :collection AND source = :source;
INSERT INTO sources(collection, source, checkpoint)
SELECT :collection, :source, :checkpoint
WHERE NOT EXISTS (SELECT 1 FROM sources WHERE collection = :collection AND source = :source);
