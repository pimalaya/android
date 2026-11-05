-- The live items an upgrade fetches: below full, holding no body, holding
-- one over a base naming a revision and no body (SYNC §5), or conflicted
-- with the remote body not captured yet; the source's handle, NULL for one
-- it does not bind.
SELECT i.link_id, b.handle FROM items i
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE i.collection = :collection AND i.deleted = 0 AND i.retained_at IS NULL
  AND (i.level < 2 OR i.object_hash IS NULL
       OR (b.base_revision IS NOT NULL AND b.base_object IS NULL)
       OR (b.conflicted = 1 AND b.conflict_object IS NULL));
