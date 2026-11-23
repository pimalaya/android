-- One item's body ('' for none, so no row and no body read apart), conflict
-- flag, diverging body, level, sort key and whether it is retained.
-- Each body by its hash.
SELECT ifnull((SELECT o.hash FROM objects o WHERE o.id = i.object), ''), i.conflicted,
       (SELECT o.hash FROM objects o WHERE o.id = i.conflict_object), i.level, i.sort_key,
       i.retained_at IS NOT NULL
FROM items i WHERE i.collection = :collection AND i.link_id = :link_id;
