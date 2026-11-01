-- Fork of UPSERT_CONTACT_SUMMARY (sha256 af5d9b5e30a257b9) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE contact_summary SET uid = :uid, fn = :fn, kind = :kind, org = :org
WHERE collection = :collection AND link_id = :link_id;
INSERT INTO contact_summary(collection, link_id, uid, fn, kind, org)
SELECT :collection, :link_id, :uid, :fn, :kind, :org
WHERE NOT EXISTS (SELECT 1 FROM contact_summary
                  WHERE collection = :collection AND link_id = :link_id);
