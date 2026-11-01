-- Every link id a collection holds, retained rows included.
SELECT link_id FROM items WHERE collection = :collection;
