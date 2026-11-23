-- A message's In-Reply-To ids in document order (Annex A.1).
SELECT value FROM summary_value
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id)
  AND role = 'in-reply-to'
ORDER BY position;
