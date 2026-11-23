-- A mail collection's live messages, oldest first, with their subject.
SELECT i.link_id, i.sort_key, s.title AS subject FROM items i
LEFT JOIN summary s ON s.item = i.id AND s.component = 'mail'
WHERE i.collection = :collection AND i.deleted = 0
ORDER BY i.sort_key ASC;
