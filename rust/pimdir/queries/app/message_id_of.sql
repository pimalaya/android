-- A message's bare Message-ID, NULL for none.
SELECT uid FROM summary
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id)
  AND component = 'mail';
