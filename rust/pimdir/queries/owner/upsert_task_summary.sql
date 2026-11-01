-- Fork of UPSERT_TASK_SUMMARY (sha256 b9770590a2c32814) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
UPDATE task_summary SET
    uid = :uid, summary = :summary, dtstart = :dtstart,
    dtstart_tzid = :dtstart_tzid, dtstart_value = :dtstart_value,
    due = :due, due_tzid = :due_tzid, due_value = :due_value,
    status = :status, completed = :completed,
    percent = :percent, recurring = :recurring, until = :until,
    priority = :priority,
    parent = :parent, mid = :mid, created = :created
WHERE collection = :collection AND link_id = :link_id;
INSERT INTO task_summary(collection, link_id, uid, summary, dtstart, dtstart_tzid,
                         dtstart_value, due, due_tzid, due_value, status, completed,
                         percent, recurring, until, priority, parent, mid, created)
SELECT :collection, :link_id, :uid, :summary, :dtstart, :dtstart_tzid,
       :dtstart_value, :due, :due_tzid, :due_value, :status, :completed,
       :percent, :recurring, :until, :priority, :parent, :mid, :created
WHERE NOT EXISTS (SELECT 1 FROM task_summary
                  WHERE collection = :collection AND link_id = :link_id);
