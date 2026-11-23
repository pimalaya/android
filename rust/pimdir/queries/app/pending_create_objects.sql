-- A collection's live pending creates and their bodies (SYNC §2).
-- Each body by its hash.
SELECT i.link_id, o.hash FROM bindings b
JOIN items i ON i.id = b.item
JOIN objects o ON o.id = i.object
WHERE b.collection = :collection AND b.source = :source
  AND b.handle >= :low AND b.handle < :high
  AND b.base_present = 0 AND i.deleted = 0 AND i.retained_at IS NULL
ORDER BY b.handle;
