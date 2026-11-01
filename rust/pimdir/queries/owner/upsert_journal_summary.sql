-- Fork of UPSERT_JOURNAL_SUMMARY (sha256 645d29e77d60f051) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE journal_summary SET
    uid = :uid, summary = :summary, dtstart = :dtstart,
    dtstart_tzid = :dtstart_tzid, dtstart_value = :dtstart_value
WHERE collection = :collection AND link_id = :link_id;
INSERT INTO journal_summary(collection, link_id, uid, summary, dtstart, dtstart_tzid, dtstart_value)
SELECT :collection, :link_id, :uid, :summary, :dtstart, :dtstart_tzid, :dtstart_value
WHERE NOT EXISTS (SELECT 1 FROM journal_summary
                  WHERE collection = :collection AND link_id = :link_id);
