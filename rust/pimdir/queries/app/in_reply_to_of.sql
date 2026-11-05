-- A message's In-Reply-To ids in document order (Annex A.1).
SELECT msg_id FROM mail_in_reply_to
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id)
ORDER BY position;
