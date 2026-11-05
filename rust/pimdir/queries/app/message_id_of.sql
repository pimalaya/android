-- A message's bare Message-ID, NULL for none.
SELECT message_id FROM mail_summary WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
