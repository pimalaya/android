-- Every binding of one item, as the cross-source merge reads them (SYNC §9),
-- each body by its hash.
SELECT b.source, b.handle, b.base_present,
       (SELECT o.hash FROM objects o WHERE o.id = b.base_object) AS base_object,
       b.base_revision, b.conflicted, b.conflict_revision,
       (SELECT o.hash FROM objects o WHERE o.id = b.conflict_object) AS conflict_object,
       (SELECT o.hash FROM objects o WHERE o.id = b.shared_object) AS shared_object
FROM bindings b
WHERE b.item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
