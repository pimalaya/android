-- Fork of UPSERT_EVENT_SUMMARY (sha256 19afee14be703282) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE event_summary SET
    uid = :uid, summary = :summary, location = :location,
    dtstart = :dtstart, dtstart_tzid = :dtstart_tzid,
    dtstart_value = :dtstart_value, dtend = :dtend,
    recurring = :recurring, until = :until
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
INSERT INTO event_summary(item, uid, summary, location, dtstart,
                          dtstart_tzid, dtstart_value, dtend, recurring, until)
SELECT i.id, :uid, :summary, :location, :dtstart,
       :dtstart_tzid, :dtstart_value, :dtend, :recurring, :until
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM event_summary WHERE item = i.id);
