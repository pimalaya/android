-- Gives an item the stored summary of another of its collection, whatever
-- its kind, row for row with its lists and addresses (Annex A): the summary
-- of a body set aside as a retained item (STORAGE §11, §11.1).
INSERT OR REPLACE INTO mail_summary(item, message_id, subject, sender,
                                    sender_name, date, size, attachment, invitation)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       message_id, subject, sender, sender_name, date, size, attachment, invitation
FROM mail_summary
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO mail_in_reply_to(item, position, message_id)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       position, message_id
FROM mail_in_reply_to
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO contact_summary(item, uid, fn, kind, org)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       uid, fn, kind, org
FROM contact_summary
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO event_summary(item, uid, summary, location, dtstart, dtstart_tzid,
                                     dtstart_value, dtend, recurring, until)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       uid, summary, location, dtstart, dtstart_tzid, dtstart_value, dtend, recurring, until
FROM event_summary
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO task_summary(item, uid, summary, dtstart, dtstart_tzid, dtstart_value,
                                    due, due_tzid, due_value, status, completed, percent,
                                    recurring, until, priority, parent, mid, created)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       uid, summary, dtstart, dtstart_tzid, dtstart_value, due, due_tzid, due_value, status,
       completed, percent, recurring, until, priority, parent, mid, created
FROM task_summary
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO task_category(item, position, value)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       position, value
FROM task_category
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO journal_summary(item, uid, summary, dtstart, dtstart_tzid, dtstart_value)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       uid, summary, dtstart, dtstart_tzid, dtstart_value
FROM journal_summary
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO file_summary(item, name, media_type, size, part)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       name, media_type, size, part
FROM file_summary
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
INSERT OR REPLACE INTO item_address(item, role, position, address, name)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       role, position, address, name
FROM item_address
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
