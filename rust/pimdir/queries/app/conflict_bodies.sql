-- A resolution's inputs: the item's body, the source's diverging and base
-- bodies, then the item's own conflict and diverging body.
-- Each body by its hash.
SELECT (SELECT o.hash FROM objects o WHERE o.id = i.object),
       (SELECT o.hash FROM objects o WHERE o.id = b.conflict_object),
       (SELECT o.hash FROM objects o WHERE o.id = b.base_object), i.conflicted,
       (SELECT o.hash FROM objects o WHERE o.id = i.conflict_object)
FROM items i
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE i.collection = :collection AND i.link_id = :link_id;
