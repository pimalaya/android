-- Gives a message the address rows of the message it was created from, on
-- copy_mail_summary's terms: its summary_value rows but its In-Reply-To ids.
INSERT OR REPLACE INTO summary_value(item, role, position, value, name)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       role, position, value, name
FROM summary_value
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id)
  AND role NOT IN ('in-reply-to', 'category');
