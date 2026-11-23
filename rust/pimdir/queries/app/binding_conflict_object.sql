-- The diverging body a source's binding waits on, by its hash, NULL for none.
SELECT (SELECT o.hash FROM objects o WHERE o.id = b.conflict_object) FROM bindings b
WHERE b.item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND b.source = :source;
