-- Every body an item's bindings pin (STORAGE §5), by its hash: each source's
-- base, its diverging remote and the shared body it last agreed with.
SELECT (SELECT o.hash FROM objects o WHERE o.id = b.base_object) AS base_object,
       (SELECT o.hash FROM objects o WHERE o.id = b.conflict_object) AS conflict_object,
       (SELECT o.hash FROM objects o WHERE o.id = b.shared_object) AS shared_object
FROM bindings b
WHERE b.item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
