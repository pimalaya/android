-- The shared body a source's binding last agreed with the item (SYNC §9), by
-- its hash.
SELECT (SELECT o.hash FROM objects o WHERE o.id = b.shared_object) FROM bindings b
WHERE b.item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND b.source = :source;
