-- The handles bound in a collection whose card another collection of its
-- account and kind stages as a pending create.
SELECT b.handle FROM items i
JOIN collections c ON c.id = i.collection
JOIN bindings b ON b.item = i.id AND b.source = :source AND b.base_present = 1
WHERE i.collection = :collection AND i.retained_at IS NULL
  AND EXISTS (SELECT 1 FROM items n
              JOIN collections nc ON nc.id = n.collection
              WHERE n.link_id = i.link_id AND n.collection <> i.collection
                AND n.deleted = 0 AND n.retained_at IS NULL
                AND n.object_hash IS NOT NULL
                AND nc.account = c.account AND nc.kind = c.kind
                AND NOT EXISTS (SELECT 1 FROM bindings h
                                WHERE h.item = n.id AND h.source = :source
                                  AND h.base_present = 1))
ORDER BY b.handle;
