-- One item's body ('' for none, so no row and no body read apart), conflict
-- flag, diverging body and level.
SELECT ifnull(object_hash, ''), conflicted, conflict_object, level
FROM items WHERE collection = :collection AND link_id = :link_id;
