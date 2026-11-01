-- Whether a collection holds an item not retained, a staged removal
-- included.
SELECT 1 FROM items
WHERE collection = :collection AND link_id = :link_id AND retained_at IS NULL;
