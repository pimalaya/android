-- Fork of UPSERT_SUMMARY (sha256 53c5afceef5a25a6) for SQLite 3.18:
-- `ON CONFLICT DO UPDATE` (3.24) as an update of the row, then its insert
-- where the item holds none, in one transaction. The update reads the old
-- `component` as the canonical text's `component` reads it.
--
-- An item's summary (Annex A), whatever its component: every column is
-- written, a column another component owns as NULL, so a calendar resource
-- rewritten as another component is this one statement. An unknown
-- invitation (NULL) never clears a known one while the row stays a mail's:
-- the message is immutable, so what its body once said it still says
-- (Annex A.1).
UPDATE summary SET
    component = :component, uid = :uid, title = :title,
    sender = :sender, sender_name = :sender_name, date = :date,
    size = :size, attachment = :attachment,
    invitation = CASE WHEN component = 'mail' AND :component = 'mail'
                      THEN coalesce(:invitation, invitation)
                      ELSE :invitation END,
    card_kind = :card_kind, org = :org, location = :location,
    dtstart = :dtstart, dtstart_tzid = :dtstart_tzid,
    dtstart_value = :dtstart_value, dtend = :dtend,
    recurring = :recurring, until = :until,
    due = :due, due_tzid = :due_tzid, due_value = :due_value,
    status = :status, completed = :completed, percent = :percent,
    priority = :priority, parent = :parent, mid = :mid,
    created = :created, media_type = :media_type, part = :part
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
INSERT INTO summary(item, component, uid, title, sender, sender_name, date, size, attachment,
                    invitation, card_kind, org, location, dtstart, dtstart_tzid, dtstart_value,
                    dtend, recurring, until, due, due_tzid, due_value, status, completed,
                    percent, priority, parent, mid, created, media_type, part)
SELECT i.id, :component,
       :uid, :title, :sender, :sender_name, :date, :size, :attachment,
       :invitation, :card_kind, :org, :location, :dtstart, :dtstart_tzid, :dtstart_value,
       :dtend, :recurring, :until, :due, :due_tzid, :due_value, :status, :completed,
       :percent, :priority, :parent, :mid, :created, :media_type, :part
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM summary WHERE item = i.id);
