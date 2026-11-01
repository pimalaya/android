-- Fork of UPSERT_FILE_SUMMARY (sha256 8cd60120b36dfe11) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE file_summary SET name = :name, media_type = :media_type, size = :size, part = :part
WHERE collection = :collection AND link_id = :link_id;
INSERT INTO file_summary(collection, link_id, name, media_type, size, part)
SELECT :collection, :link_id, :name, :media_type, :size, :part
WHERE NOT EXISTS (SELECT 1 FROM file_summary
                  WHERE collection = :collection AND link_id = :link_id);
