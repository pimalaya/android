-- The binding a pending create copies from (SYNC §3, origin_for_link), in its
-- account: never one under a provisional handle, a delivered create naming no
-- member to copy from.
SELECT b.collection, b.handle FROM bindings b
JOIN items i ON i.id = b.item
WHERE i.link_id IN (:link_id, CASE WHEN :link_id GLOB 'dup:*'
                                   THEN substr(:link_id, 5, (length(:link_id) - 6) / 2) END)
  AND (i.link_id = :link_id OR 'dup:' || i.link_id || '#' || char(1) || i.link_id = :link_id)
  AND b.source = :source AND b.collection <> :collection
  AND substr(b.collection, 1, length(:account)) = :account
  AND b.base_present = 1 AND substr(b.handle, 1, 1) <> char(1)
  AND (:object IS NULL OR b.base_object = :object)
  AND i.deleted = 0 AND i.retained_at IS NULL
ORDER BY i.link_id <> :link_id, b.collection
LIMIT 1;
