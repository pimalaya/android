-- Every binding of one item, as the cross-source merge reads them (SYNC §9).
SELECT source, handle, base_present, base_object, base_revision,
       conflicted, conflict_revision, conflict_object, shared_object
FROM bindings WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
