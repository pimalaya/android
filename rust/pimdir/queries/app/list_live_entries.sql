-- Every live item of every collection of a kind holding a body, with the
-- source's handle and revision.
-- The body by its hash.
SELECT i.collection, i.link_id, o.hash, b.base_revision, b.handle
FROM items i
JOIN collections c ON c.id = i.collection
JOIN objects o ON o.id = i.object
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE c.kind = :kind AND i.deleted = 0
  AND i.retained_at IS NULL;
