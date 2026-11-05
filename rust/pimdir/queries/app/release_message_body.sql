-- Takes a message back to its summary (STORAGE §11.4): a base or a shared
-- body naming its own body lets go of it, a message having no cross-source
-- merge to read it for, and the binding stays based; then the item drops
-- its body, in one transaction.
UPDATE bindings SET base_object = NULL, base_present = 1
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND base_object = :object_hash;
UPDATE bindings SET shared_object = NULL
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND shared_object = :object_hash;
UPDATE items SET object_hash = NULL, level = 1
WHERE collection = :collection AND link_id = :link_id;
