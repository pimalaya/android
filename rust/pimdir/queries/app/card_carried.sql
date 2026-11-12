-- Whether another placement of a card on its account stages, unsent and
-- unconflicted over a base this source agreed, the body a create carries.
SELECT 1 FROM items i
JOIN collections c ON c.id = i.collection
JOIN items o ON o.link_id = i.link_id AND o.collection <> i.collection
JOIN collections oc ON oc.id = o.collection
JOIN bindings b ON b.item = o.id AND b.source = :source AND b.base_present = 1
WHERE i.collection = :collection AND i.link_id = :link_id
  AND o.deleted = 0 AND o.retained_at IS NULL AND o.conflicted = 0
  AND o.object_hash = i.object_hash AND b.conflicted = 0
  AND (b.base_object IS NULL OR b.base_object <> o.object_hash)
  AND oc.account = c.account AND oc.kind = c.kind
LIMIT 1;
