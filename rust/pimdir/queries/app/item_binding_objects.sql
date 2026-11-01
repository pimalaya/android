-- Every body an item's bindings pin (STORAGE §5): each source's base, its
-- diverging remote and the shared body it last agreed with.
SELECT base_object, conflict_object, shared_object FROM bindings
WHERE collection = :collection AND link_id = :link_id;
