-- Gives an item the stored summary of another of its collection, whatever
-- its kind, row for row with its lists and addresses (Annex A): the summary
-- of a body set aside as a retained item (STORAGE §11, §11.1).
INSERT OR REPLACE INTO summary(item, component, uid, title, sender, sender_name, date, size,
                               attachment, invitation, card_kind, org, location, dtstart,
                               dtstart_tzid, dtstart_value, dtend, recurring, until, due,
                               due_tzid, due_value, status, completed, percent, priority,
                               parent, mid, created, media_type, part)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       component, uid, title, sender, sender_name, date, size, attachment, invitation,
       card_kind, org, location, dtstart, dtstart_tzid, dtstart_value, dtend, recurring,
       until, due, due_tzid, due_value, status, completed, percent, priority, parent, mid,
       created, media_type, part
FROM summary
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO summary_value(item, role, position, value, name)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       role, position, value, name
FROM summary_value
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
