-- A resolution's inputs: the item's body, the source's diverging and base
-- bodies, then the item's own conflict and diverging body.
SELECT i.object_hash, b.conflict_object, b.base_object, i.conflicted, i.conflict_object
FROM items i
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE i.collection = :collection AND i.link_id = :link_id;
