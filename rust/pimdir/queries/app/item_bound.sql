-- Whether any source binds an item.
SELECT 1 FROM bindings WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) LIMIT 1;
