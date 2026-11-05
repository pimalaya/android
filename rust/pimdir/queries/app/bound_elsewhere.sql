-- Where else a source binds a live item of this link id, its collection and
-- handle: the origin a create of an identity held elsewhere is a membership
-- addition from.
SELECT b.collection, b.handle FROM bindings b
JOIN items i ON i.id = b.item
WHERE i.link_id = :link_id AND b.source = :source AND b.collection <> :collection
  AND i.deleted = 0 AND i.retained_at IS NULL
LIMIT 1;
