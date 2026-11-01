-- Drops one item outright, whatever binds it, its bindings, flags, summary
-- and addresses going by cascade; the caller releases every body it pinned
-- (item_objects, item_binding_objects) in the same transaction.
DELETE FROM items WHERE collection = :collection AND link_id = :link_id;
