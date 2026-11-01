-- A source's live conflicts whose remote body is captured, with what a
-- resolution merges: the item's body, the base's and the remote's, the
-- handle and the revisions.
SELECT i.link_id, i.object_hash, b.handle, b.base_object,
       b.base_revision, b.conflict_revision, b.conflict_object
FROM items i
JOIN bindings b ON b.collection = i.collection AND b.link_id = i.link_id
               AND b.source = :source
WHERE i.collection = :collection AND i.deleted = 0
  AND b.conflicted = 1 AND b.conflict_object IS NOT NULL;
