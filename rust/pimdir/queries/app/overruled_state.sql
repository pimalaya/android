-- What an overrule settles (SYNC §7, §9): the item's conflict, diverging
-- body, body, sort key and flags, then the source's binding conflict and its
-- diverging body.
SELECT i.conflicted, i.conflict_object, i.object_hash, i.sort_key,
       CASE WHEN i.flags_known = 1
            THEN coalesce((SELECT group_concat(f.flag, ' ') FROM placement_flag f
                           WHERE f.collection = i.collection AND f.link_id = i.link_id), '') END AS flags,
       b.conflicted, b.conflict_object
FROM items i
LEFT JOIN bindings b ON b.collection = i.collection AND b.link_id = i.link_id
                    AND b.source = :source
WHERE i.collection = :collection AND i.link_id = :link_id;
