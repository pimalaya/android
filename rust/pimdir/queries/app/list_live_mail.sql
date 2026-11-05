-- A mail collection's live messages, oldest first, with their subject.
SELECT i.link_id, i.sort_key, s.subject FROM items i
LEFT JOIN mail_summary s ON s.item = i.id
WHERE i.collection = :collection AND i.deleted = 0
ORDER BY i.sort_key ASC;
