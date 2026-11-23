-- A source's binding as an upsert writes it, its handle repointed only on a
-- rebind the caller checked, as an update of the row, then its insert where
-- none is there, in one transaction (SQLite 3.18 has no upsert). Each body is
-- named by its hash and written as its `objects` id; the server object id is
-- the one the placement states (SYNC §4), NULL where it states none.
UPDATE bindings SET handle = :handle, base_flags_known = :base_flags_known,
       base_marks = CASE WHEN :base_flags_known = 1 THEN base_marks ELSE 0 END,
       base_keywords = CASE WHEN :base_flags_known = 1 THEN base_keywords ELSE '' END,
       base_object = (SELECT id FROM objects WHERE hash = :base_object),
       base_revision = :base_revision,
       base_present = :base_present, conflicted = :conflicted,
       conflict_revision = :conflict_revision,
       conflict_object = (SELECT id FROM objects WHERE hash = :conflict_object),
       shared_object = (SELECT id FROM objects WHERE hash = :shared_object),
       server_object_id = :server_object_id
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND source = :source;
INSERT INTO bindings(item, collection, source, handle, base_flags_known, base_object,
                     base_revision, base_present, conflicted, conflict_revision,
                     conflict_object, shared_object, server_object_id)
SELECT i.id, i.collection, :source, :handle, :base_flags_known,
       (SELECT id FROM objects WHERE hash = :base_object),
       :base_revision, :base_present, :conflicted, :conflict_revision,
       (SELECT id FROM objects WHERE hash = :conflict_object),
       (SELECT id FROM objects WHERE hash = :shared_object), :server_object_id
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM bindings WHERE item = i.id AND source = :source);
