-- The items of every collection of a kind holding a body and no
-- summary row, for a summary repair.
SELECT i.collection, i.link_id, o.hash FROM items i
JOIN collections c ON c.id = i.collection
JOIN objects o ON o.id = i.object
LEFT JOIN summary s ON s.item = i.id
WHERE c.kind = :kind AND s.item IS NULL;
