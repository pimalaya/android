-- An address book's live items holding a body, in store order, with the
-- server's binding (its conflict, diverging body, handle and revision) and
-- the item's own conflict: what a contact row renders.
SELECT i.link_id, i.object_hash, b.conflicted,
       b.conflict_object, b.handle, b.base_revision,
       i.conflicted, i.conflict_object
FROM items i
LEFT JOIN bindings b ON b.collection = i.collection AND b.link_id = i.link_id
                    AND b.source = :source
WHERE i.collection = :collection AND i.deleted = 0
  AND i.retained_at IS NULL AND i.object_hash IS NOT NULL
ORDER BY i.sort_key;
