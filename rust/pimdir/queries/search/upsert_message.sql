-- Fork of UPSERT_MESSAGE (sha256 f623f4c335eb36b3) for SQLite 3.18: the
-- upsert (3.24) as an update of the row by key, then its insert where none
-- is there, run in one transaction.
UPDATE message SET thread = :thread, parent = :parent WHERE link_id = :link_id;
INSERT INTO message(link_id, thread, parent)
SELECT :link_id, :thread, :parent
WHERE NOT EXISTS (SELECT 1 FROM message WHERE link_id = :link_id);
