-- A collection's pending creates holding a body, each with it: live items
-- this source binds there with no base. What the report of the creates a push
-- holds back reads, planning each as its push would be.
SELECT i.link_id, i.object_hash FROM items i
WHERE i.collection = :collection AND i.deleted = 0 AND i.retained_at IS NULL
  AND i.object_hash IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM bindings b
                  WHERE b.item = i.id AND b.source = :source AND b.base_present = 1)
ORDER BY i.link_id;
