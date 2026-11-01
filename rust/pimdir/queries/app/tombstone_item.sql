-- Marks an item removed while another source still binds it: a tombstone
-- that source's next pass pushes as a removal, the last retiring the item.
UPDATE items SET deleted = 1 WHERE collection = :collection AND link_id = :link_id;
