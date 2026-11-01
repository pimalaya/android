-- Whether a source has anything to reconcile in a collection: a conflict, a
-- removed or retained item it still binds, or a live body moved past its
-- base.
SELECT EXISTS (
  SELECT 1 FROM items i
  LEFT JOIN bindings b ON b.collection = i.collection AND b.link_id = i.link_id
                      AND b.source = :source
  WHERE i.collection = :collection
    AND (b.conflicted = 1
         OR ((i.deleted = 1 OR i.retained_at IS NOT NULL) AND b.handle IS NOT NULL)
         OR (i.deleted = 0 AND i.retained_at IS NULL AND i.object_hash IS NOT NULL
             AND (b.base_object IS NULL OR b.base_object <> i.object_hash))));
