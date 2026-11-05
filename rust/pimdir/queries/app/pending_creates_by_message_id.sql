-- A collection's live pending mail creates by Message-ID, read off the range
-- of provisional handles [:low, :high) (SYNC §2), so the cost follows the
-- creates.
SELECT i.link_id, s.message_id FROM bindings b
JOIN items i ON i.id = b.item
JOIN mail_summary s ON s.item = b.item
WHERE b.collection = :collection AND b.source = :source
  AND b.handle >= :low AND b.handle < :high
  AND b.base_present = 0 AND i.deleted = 0
  AND s.message_id IS NOT NULL;
