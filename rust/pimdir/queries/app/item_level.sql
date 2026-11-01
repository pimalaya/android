-- The detail level an item is held at (STORAGE §13).
SELECT level FROM items WHERE collection = :collection AND link_id = :link_id;
