-- The diverging body an item's own conflict recorded, NULL for none.
SELECT conflict_object FROM items WHERE collection = :collection AND link_id = :link_id;
