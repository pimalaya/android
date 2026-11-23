-- An item's own conflict, as the cross-source merge settles it (SYNC §9), its
-- diverging body named by its hash.
UPDATE items SET conflicted = :conflicted,
       conflict_object = (SELECT id FROM objects WHERE hash = :conflict_object)
WHERE collection = :collection AND link_id = :link_id;
