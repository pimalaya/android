-- The cards this source binds in a collection that another collection of the
-- account only stages, under the body agreed here: copies a tick made, which
-- follow a pull of the card here rather than stand as an edit of their own.
SELECT n.collection, t.link_id FROM items t
JOIN bindings b ON b.item = t.id
JOIN items n ON n.link_id = t.link_id
JOIN collections c ON c.id = n.collection
WHERE t.collection = :collection AND t.deleted = 0
  AND b.source = :source AND b.base_present = 1
  AND n.collection <> t.collection AND n.deleted = 0 AND n.retained_at IS NULL
  AND n.object_hash = b.base_object
  AND NOT EXISTS (SELECT 1 FROM bindings h
                  WHERE h.item = n.id AND h.source = :source AND h.base_present = 1)
  AND c.account = (SELECT account FROM collections WHERE id = :collection)
  AND c.kind = (SELECT kind FROM collections WHERE id = :collection)
ORDER BY n.collection, t.link_id;
