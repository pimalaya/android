-- The binding a pending create copies from (SYNC §3, origin_for_link), in
-- another collection of its account and kind: never one under a provisional
-- handle, a delivered create naming no member to copy from. A holder whose
-- removal is staged counts, as the canonical rule has it, a live one first.
-- The account is the roster's, every source of the app being named alike.
SELECT b.collection, b.handle FROM bindings b
JOIN items i ON i.id = b.item
JOIN collections c ON c.id = b.collection
WHERE i.link_id IN (:link_id, CASE WHEN :link_id GLOB 'dup:*'
                                   THEN substr(:link_id, 5, (length(:link_id) - 6) / 2) END)
  AND (i.link_id = :link_id OR 'dup:' || i.link_id || '#' || char(1) || i.link_id = :link_id)
  AND b.source = :source AND b.collection <> :collection
  AND b.base_present = 1 AND substr(b.handle, 1, 1) <> char(1)
  AND (:object IS NULL OR b.base_object = :object)
  AND c.account IS (SELECT account FROM collections WHERE id = :collection)
  AND c.kind = (SELECT kind FROM collections WHERE id = :collection)
ORDER BY i.link_id <> :link_id, i.deleted, b.collection
LIMIT 1;
