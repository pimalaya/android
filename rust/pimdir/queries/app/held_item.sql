-- One item's body ('' for none, so no row and no body read apart), conflict
-- flag, diverging body, level, sort key and whether it is retained.
SELECT ifnull(object_hash, ''), conflicted, conflict_object, level, sort_key,
       retained_at IS NOT NULL
FROM items WHERE collection = :collection AND link_id = :link_id;
