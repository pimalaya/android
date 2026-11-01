-- One item's body, NULL for none; no row for no item.
SELECT object_hash FROM items WHERE collection = :collection AND link_id = :link_id;
