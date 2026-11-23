-- A source's live conflicts whose remote body is captured, with what a
-- resolution merges: the item's body, the base's and the remote's, the
-- handle and the revisions, and the item's own conflict, which a resolution
-- naming the same diverging body settles too (SYNC §7).
-- Each body by its hash.
SELECT i.link_id, (SELECT o.hash FROM objects o WHERE o.id = i.object) AS object_hash, b.handle,
       (SELECT o.hash FROM objects o WHERE o.id = b.base_object) AS base_object,
       b.base_revision, b.conflict_revision,
       (SELECT o.hash FROM objects o WHERE o.id = b.conflict_object) AS conflict_object,
       i.conflicted,
       (SELECT o.hash FROM objects o WHERE o.id = i.conflict_object) AS item_conflict_object
FROM items i
JOIN bindings b ON b.item = i.id
               AND b.source = :source
WHERE i.collection = :collection AND i.deleted = 0
  AND b.conflicted = 1 AND b.conflict_object IS NOT NULL;
