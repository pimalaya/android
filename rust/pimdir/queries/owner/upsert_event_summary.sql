-- Fork of UPSERT_EVENT_SUMMARY (sha256 633d89cdc62ad8ea) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE event_summary SET
    uid = :uid, summary = :summary, location = :location,
    dtstart = :dtstart, dtstart_tzid = :dtstart_tzid,
    dtstart_value = :dtstart_value, dtend = :dtend,
    recurring = :recurring, until = :until
WHERE collection = :collection AND link_id = :link_id;
INSERT INTO event_summary(collection, link_id, uid, summary, location, dtstart,
                          dtstart_tzid, dtstart_value, dtend, recurring, until)
SELECT :collection, :link_id, :uid, :summary, :location, :dtstart,
       :dtstart_tzid, :dtstart_value, :dtend, :recurring, :until
WHERE NOT EXISTS (SELECT 1 FROM event_summary
                  WHERE collection = :collection AND link_id = :link_id);
