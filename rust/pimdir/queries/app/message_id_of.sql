-- A message's bare Message-ID, NULL for none.
SELECT message_id FROM mail_summary WHERE collection = :collection AND link_id = :link_id;
