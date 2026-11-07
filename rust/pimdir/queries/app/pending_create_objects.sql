-- A collection's live pending creates and their bodies (SYNC §2).
SELECT i.link_id, i.object_hash FROM bindings b
JOIN items i ON i.id = b.item
WHERE b.collection = :collection AND b.source = :source
  AND b.handle >= :low AND b.handle < :high
  AND b.base_present = 0 AND i.deleted = 0 AND i.retained_at IS NULL
  AND i.object_hash IS NOT NULL
ORDER BY b.handle;
