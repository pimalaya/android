-- A source's binding as an upsert writes it, its handle repointed only on a
-- rebind the caller checked, as an update of the row, then its insert where
-- none is there, in one transaction (SQLite 3.18 has no upsert).
UPDATE bindings SET handle = :handle, base_flags_known = :base_flags_known,
       base_marks = CASE WHEN :base_flags_known = 1 THEN base_marks ELSE 0 END,
       base_object = :base_object, base_revision = :base_revision,
       base_present = :base_present, conflicted = :conflicted,
       conflict_revision = :conflict_revision, conflict_object = :conflict_object,
       shared_object = :shared_object
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND source = :source;
INSERT INTO bindings(item, collection, source, handle, base_flags_known, base_object,
                     base_revision, base_present, conflicted, conflict_revision,
                     conflict_object, shared_object)
SELECT i.id, i.collection, :source, :handle, :base_flags_known, :base_object,
       :base_revision, :base_present, :conflicted, :conflict_revision,
       :conflict_object, :shared_object
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM bindings WHERE item = i.id AND source = :source);
