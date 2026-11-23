-- What an overrule settles (SYNC §7, §9): the item's conflict, diverging
-- body, body, sort key and flags, then the source's binding conflict and its
-- diverging body, each body by its hash.
SELECT i.conflicted, (SELECT o.hash FROM objects o WHERE o.id = i.conflict_object),
       (SELECT o.hash FROM objects o WHERE o.id = i.object), i.sort_key,
       CASE WHEN i.flags_known = 1
            THEN trim(CASE WHEN i.marks & 1 THEN '\Seen ' ELSE '' END
                   || CASE WHEN i.marks & 2 THEN '\Answered ' ELSE '' END
                   || CASE WHEN i.marks & 4 THEN '\Flagged ' ELSE '' END
                   || CASE WHEN i.marks & 8 THEN '\Draft ' ELSE '' END
                   || CASE WHEN i.marks & 16 THEN '\Deleted ' ELSE '' END
                   || i.keywords) END AS flags,
       b.conflicted, (SELECT o.hash FROM objects o WHERE o.id = b.conflict_object)
FROM items i
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE i.collection = :collection AND i.link_id = :link_id;
