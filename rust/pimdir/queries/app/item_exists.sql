-- Whether a collection holds an item under a link id, retained or not.
SELECT 1 FROM items WHERE collection = :collection AND link_id = :link_id;
