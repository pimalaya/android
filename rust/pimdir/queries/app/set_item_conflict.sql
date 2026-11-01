-- An item's own conflict, as the cross-source merge settles it (SYNC §9).
UPDATE items SET conflicted = :conflicted, conflict_object = :conflict_object
WHERE collection = :collection AND link_id = :link_id;
