-- A collection's staged removals of cards live elsewhere on its account,
-- each with every body this source agreed for the card there, and the body
-- of the create a move superseding it carries.
SELECT b.handle, b.base_revision, o.base_object FROM items i
JOIN collections c ON c.id = i.collection
JOIN bindings b ON b.item = i.id AND b.source = :source AND b.base_present = 1
JOIN items n ON n.link_id = i.link_id
JOIN collections nc ON nc.id = n.collection
JOIN bindings o ON o.item = n.id AND o.source = :source AND o.base_present = 1
WHERE i.collection = :collection AND i.deleted = 1 AND i.retained_at IS NULL
  AND o.base_object IS NOT NULL
  AND nc.account = c.account AND nc.kind = c.kind
  AND EXISTS (SELECT 1 FROM items l
              JOIN collections lc ON lc.id = l.collection
              WHERE l.link_id = i.link_id AND l.collection <> i.collection
                AND l.deleted = 0 AND l.retained_at IS NULL
                AND lc.account = c.account AND lc.kind = c.kind)
UNION
SELECT b.handle, b.base_revision, n.object_hash FROM items i
JOIN bindings b ON b.item = i.id AND b.source = :source AND b.base_present = 1
JOIN supersessions s ON s.item = i.id
JOIN items n ON n.id = s.by_item
WHERE i.collection = :collection AND i.deleted = 1 AND i.retained_at IS NULL
  AND n.deleted = 0 AND n.retained_at IS NULL AND n.object_hash IS NOT NULL
ORDER BY 1;
