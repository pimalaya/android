-- Every live item of every collection of a kind holding a body, with the
-- source's handle and revision.
SELECT i.collection, i.link_id, i.object_hash, b.base_revision, b.handle
FROM items i
JOIN collections c ON c.id = i.collection
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE c.kind = :kind AND i.deleted = 0
  AND i.retained_at IS NULL
  AND i.object_hash IS NOT NULL;
