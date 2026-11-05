-- Fork of UPSERT_FILE_SUMMARY (sha256 53a64ffe6a1c8949) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE file_summary SET name = :name, media_type = :media_type, size = :size, part = :part
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
INSERT INTO file_summary(item, name, media_type, size, part)
SELECT i.id, :name, :media_type, :size, :part
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM file_summary WHERE item = i.id);
