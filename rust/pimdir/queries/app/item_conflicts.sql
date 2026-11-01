-- The live items two sources diverged on (SYNC §9) whose bodies are both
-- held, on a source's binding, a binding holding its own captured conflict
-- left to binding_conflicts.
SELECT i.link_id, i.object_hash, b.handle, b.base_revision, i.conflict_object
FROM items i
JOIN bindings b ON b.collection = i.collection AND b.link_id = i.link_id
               AND b.source = :source
WHERE i.collection = :collection AND i.deleted = 0
  AND i.retained_at IS NULL AND i.conflicted = 1
  AND i.conflict_object IS NOT NULL
  AND i.object_hash IS NOT NULL
  AND NOT (b.conflicted = 1 AND b.conflict_object IS NOT NULL);
