-- The body a source last agreed on, NULL for none.
SELECT base_object FROM bindings
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND source = :source;
