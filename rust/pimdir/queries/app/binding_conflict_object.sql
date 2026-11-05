-- The diverging body a source's binding waits on, NULL for none.
SELECT conflict_object FROM bindings
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND source = :source;
