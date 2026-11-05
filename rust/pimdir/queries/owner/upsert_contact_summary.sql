-- Fork of UPSERT_CONTACT_SUMMARY (sha256 066b3a67bddf7dcd) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE contact_summary SET uid = :uid, fn = :fn, kind = :kind, org = :org
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
INSERT INTO contact_summary(item, uid, fn, kind, org)
SELECT i.id, :uid, :fn, :kind, :org
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM contact_summary WHERE item = i.id);
