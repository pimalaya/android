-- The body a source last agreed on, by its hash, NULL for none.
SELECT (SELECT o.hash FROM objects o WHERE o.id = b.base_object) FROM bindings b
WHERE b.item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND b.source = :source;
