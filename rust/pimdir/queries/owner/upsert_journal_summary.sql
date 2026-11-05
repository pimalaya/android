-- Fork of UPSERT_JOURNAL_SUMMARY (sha256 2e759c7c3f09ff8c) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE journal_summary SET
    uid = :uid, summary = :summary, dtstart = :dtstart,
    dtstart_tzid = :dtstart_tzid, dtstart_value = :dtstart_value
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
INSERT INTO journal_summary(item, uid, summary, dtstart, dtstart_tzid, dtstart_value)
SELECT i.id, :uid, :summary, :dtstart, :dtstart_tzid, :dtstart_value
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM journal_summary WHERE item = i.id);
