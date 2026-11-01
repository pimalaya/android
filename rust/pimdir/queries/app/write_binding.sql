-- A source's binding as an upsert writes it, its handle repointed only on a
-- rebind the caller checked, as an update of the row, then its insert where
-- none is there, in one transaction (SQLite 3.18 has no upsert).
UPDATE bindings SET handle = :handle, base_flags_known = :base_flags_known,
       base_object = :base_object, base_revision = :base_revision,
       base_present = :base_present, conflicted = :conflicted,
       conflict_revision = :conflict_revision, conflict_object = :conflict_object,
       shared_object = :shared_object
WHERE collection = :collection AND link_id = :link_id AND source = :source;
INSERT INTO bindings(collection, link_id, source, handle, base_flags_known, base_object,
                     base_revision, base_present, conflicted, conflict_revision,
                     conflict_object, shared_object)
SELECT :collection, :link_id, :source, :handle, :base_flags_known, :base_object,
       :base_revision, :base_present, :conflicted, :conflict_revision,
       :conflict_object, :shared_object
WHERE NOT EXISTS (SELECT 1 FROM bindings
                  WHERE collection = :collection AND link_id = :link_id
                    AND source = :source);
