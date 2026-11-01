-- The items of every collection of a kind holding a body and no
-- contact_summary row, for a summary repair.
SELECT i.collection, i.link_id, i.object_hash FROM items i
JOIN collections c ON c.id = i.collection
LEFT JOIN contact_summary s ON s.collection = i.collection AND s.link_id = i.link_id
WHERE c.kind = :kind AND i.object_hash IS NOT NULL AND s.link_id IS NULL;
